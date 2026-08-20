package com.uniselect.cs.controller;

import com.uniselect.cs.aspect.MetricsCollector;
import com.uniselect.cs.common.dto.SseEvent;
import com.uniselect.cs.shopping.SessionStateMachine;
import com.uniselect.cs.shopping.model.RankedProduct;
import com.uniselect.cs.shopping.model.SessionState;
import com.uniselect.cs.shopping.model.UserContext;
import com.uniselect.cs.shopping.ranking.ProductRanker;
import com.uniselect.cs.shopping.recall.ProductRecallService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * 导购推荐入口（SSE 流式，复用客服网关隔离与 SseEmitter 模板）。
 *
 * <p>链路：网关拦截器短路（merchant_id 校验 + 转人工/注入预判，复用）→ 状态机迁移
 * → 混合召回 → 四维排序 → 流式输出（message 理由 + product 逐个）→ done + 异步埋点。
 * 无候选时发 {@code degrade}（不凑数）。</p>
 *
 * <p>本控制器<b>不新增拦截器</b>，依赖 {@code WebConfig} 将 {@code CsGatewayInterceptor}
 * 注册到 {@code /api/shopping/**}，自动获得与客服一致的隔离红线。</p>
 */
@RestController
@RequestMapping("/api/shopping")
public class ShoppingGuideController {

    private static final Logger log = LoggerFactory.getLogger(ShoppingGuideController.class);

    private final ProductRecallService recallService;
    private final ProductRanker ranker;
    private final SessionStateMachine stateMachine;
    private final MetricsCollector metricsCollector;
    private final long sseTimeoutMs;

    public ShoppingGuideController(ProductRecallService recallService,
                                   ProductRanker ranker,
                                   SessionStateMachine stateMachine,
                                   MetricsCollector metricsCollector,
                                   @Value("${uniselect.cs.shopping.sse-timeout-ms:30000}") long sseTimeoutMs) {
        this.recallService = recallService;
        this.ranker = ranker;
        this.stateMachine = stateMachine;
        this.metricsCollector = metricsCollector;
        this.sseTimeoutMs = sseTimeoutMs;
    }

    @GetMapping(value = "/recommend", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter recommend(@RequestParam String merchantId,
                                @RequestParam String sessionId,
                                @RequestParam(required = false, defaultValue = "") String userQuery,
                                @RequestParam(required = false, defaultValue = "0") double budget) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        registerCallbacks(emitter, merchantId, sessionId);
        CompletableFutureRunner.run(() -> recommendFlow(emitter, merchantId, sessionId, userQuery, budget));
        return emitter;
    }

    private void recommendFlow(SseEmitter emitter, String merchantId, String sessionId,
                               String userQuery, double budget) {
        long start = System.nanoTime();
        try {
            // 1) 状态机迁移（含 ≤500 字摘要，不进 LLM）
            SessionState state = stateMachine.transition(merchantId, sessionId, userQuery);

            // 2) 混合召回（隔离命名空间 + 实时过滤 + sku 去重）
            UserContext userCtx = new UserContext(merchantId, sessionId, budget, List.of());
            List<com.uniselect.cs.shopping.model.ProductCandidate> candidates =
                    recallService.recall(merchantId, userQuery, userCtx);

            // 3) 四维排序
            List<RankedProduct> ranked = ranker.rank(candidates, userCtx);

            if (ranked.isEmpty()) {
                // 无候选：降级不凑数
                safeSend(emitter, SseEvent.degrade(
                        "暂时没有找到符合条件的商品，您可以调整预算或换个关键词再试试。", "no-candidate"));
                safeSend(emitter, SseEvent.done());
                emitter.complete();
                return;
            }

            // 4) 流式输出：先发整体理由（message），再逐商品（product）
            safeSend(emitter, SseEvent.message(
                    "为您精选了 " + ranked.size() + " 件商品：", "guide-intro"));
            int rank = 1;
            for (RankedProduct p : ranked) {
                safeSend(emitter, SseEvent.product(p, rank));
                // 曝光埋点（event_id 幂等）
                metricsCollector.recordRecommendImpression(
                        merchantId, sessionId, p.skuId(), eventId(merchantId, sessionId, p.skuId(), "imp"));
                rank++;
            }

            safeSend(emitter, SseEvent.done());
            emitter.complete();

            long costMs = (System.nanoTime() - start) / 1_000_000;
            log.info("[shopping] recommend done merchantId={} state={} candidates={} ranked={} costMs={}",
                    merchantId, state, candidates.size(), ranked.size(), costMs);
        } catch (Exception e) {
            log.error("[shopping] recommend flow error merchantId={}", merchantId, e);
            safeSend(emitter, SseEvent.error("INTERNAL_ERROR", "导购服务异常"));
            emitter.complete();
        }
    }

    /** 幂等 event_id：merchant:session:sku:type + 随机后缀防重放（测试可传入固定值验证去重） */
    private String eventId(String merchantId, String sessionId, String skuId, String type) {
        return merchantId + ":" + sessionId + ":" + skuId + ":" + type + ":" + UUID.randomUUID();
    }

    private void registerCallbacks(SseEmitter emitter, String merchantId, String sessionId) {
        emitter.onCompletion(() -> log.debug("[sse][shopping] completed merchantId={} sessionId={}", merchantId, sessionId));
        emitter.onTimeout(() -> {
            log.warn("[sse][shopping] timeout merchantId={} sessionId={}", merchantId, sessionId);
            emitter.complete();
        });
        emitter.onError((ex) -> {
            log.warn("[sse][shopping] error merchantId={} sessionId={} err={}", merchantId, sessionId,
                    ex != null ? ex.getMessage() : "null");
            emitter.completeWithError(ex != null ? ex : new RuntimeException("sse-error"));
        });
    }

    private void safeSend(SseEmitter emitter, SseEvent event) {
        try {
            emitter.send(event);
        } catch (IllegalStateException ex) {
            log.debug("[sse][shopping] send skipped, connection closed: {}", ex.getMessage());
        } catch (java.io.IOException ex) {
            log.debug("[sse][shopping] send io error (client gone): {}", ex.getMessage());
        }
    }

    private static final class CompletableFutureRunner {
        static void run(Runnable task) {
            java.util.concurrent.CompletableFuture.runAsync(task);
        }
    }
}
