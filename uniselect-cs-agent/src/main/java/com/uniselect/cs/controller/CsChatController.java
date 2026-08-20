package com.uniselect.cs.controller;

import com.uniselect.cs.aspect.MetricsCollector;
import com.uniselect.cs.common.dto.ChatRequest;
import com.uniselect.cs.common.dto.ChatTurn;
import com.uniselect.cs.common.dto.IntentResult;
import com.uniselect.cs.common.dto.RagRetrieveResult;
import com.uniselect.cs.common.dto.SseEvent;
import com.uniselect.cs.common.dto.StreamChunk;
import com.uniselect.cs.common.dto.ToolUseResult;
import com.uniselect.cs.service.FinalInspector;
import com.uniselect.cs.service.IntentRecognitionService;
import com.uniselect.cs.service.LlmClientRouter;
import com.uniselect.cs.service.MerchantConfigService;
import com.uniselect.cs.service.PromptAssembler;
import com.uniselect.cs.service.RagService;
import com.uniselect.cs.service.SessionContextService;
import com.uniselect.cs.service.StreamViolationScanner;
import com.uniselect.cs.service.ToolUsePrefetchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 客服对话入口（SSE 流式，Step 4）。
 *
 * <p>链路：拦截器短路 → 防呆校验 → 意图识别 → 并行 Tool Use 预取 → RAG 检索 →
 * 两层 Prompt 组装 → <b>LLM 流式生成（SseEmitter 逐 chunk 推送）</b>。
 * 流式过程中：第二层拦截（生成中增量扫描越权词，命中即截断 + 降级事件）；
 * 流结束后：第三层终检（发现问题发 revoke 告警事件，不阻塞已发送）。</p>
 */
@RestController
@RequestMapping("/api/cs")
public class CsChatController {

    private static final Logger log = LoggerFactory.getLogger(CsChatController.class);

    private final IntentRecognitionService intentService;
    private final ToolUsePrefetchService prefetchService;
    private final RagService ragService;
    private final PromptAssembler promptAssembler;
    private final MerchantConfigService merchantConfigService;
    private final LlmClientRouter llmRouter;
    private final StreamViolationScanner layer2Scanner;
    private final FinalInspector layer3Inspector;
    private final MetricsCollector metricsCollector;
    private final SessionContextService sessionContextService;
    private final int ragTopK;
    private final long sseTimeoutMs;

    public CsChatController(IntentRecognitionService intentService,
                            ToolUsePrefetchService prefetchService,
                            RagService ragService,
                            PromptAssembler promptAssembler,
                            MerchantConfigService merchantConfigService,
                            LlmClientRouter llmRouter,
                            StreamViolationScanner layer2Scanner,
                            FinalInspector layer3Inspector,
                            MetricsCollector metricsCollector,
                            SessionContextService sessionContextService,
                            @Value("${uniselect.cs.rag.top-k:5}") int ragTopK,
                            @Value("${uniselect.cs.sse.timeout-ms:60000}") long sseTimeoutMs) {
        this.intentService = intentService;
        this.prefetchService = prefetchService;
        this.ragService = ragService;
        this.promptAssembler = promptAssembler;
        this.merchantConfigService = merchantConfigService;
        this.llmRouter = llmRouter;
        this.layer2Scanner = layer2Scanner;
        this.layer3Inspector = layer3Inspector;
        this.metricsCollector = metricsCollector;
        this.sessionContextService = sessionContextService;
        this.ragTopK = ragTopK;
        this.sseTimeoutMs = sseTimeoutMs;
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatGet(@RequestParam String sessionId,
                              @RequestParam String merchantId,
                              @RequestParam String message) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        registerCallbacks(emitter, merchantId, sessionId);
        // 异步执行流式（不阻塞 Servlet 容器线程）
        CompletableFutureRunner.run(() -> streamFlow(emitter, sessionId, merchantId, message));
        return emitter;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatPost(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        registerCallbacks(emitter, request.merchantId(), request.sessionId());
        CompletableFutureRunner.run(() -> streamFlow(emitter, request.sessionId(), request.merchantId(), request.message()));
        return emitter;
    }

    /** 注册 SSE 生命周期回调，确保任何情况下连接正确释放（防泄漏） */
    private void registerCallbacks(SseEmitter emitter, String merchantId, String sessionId) {
        emitter.onCompletion(() -> log.debug("[sse] completed merchantId={} sessionId={}", merchantId, sessionId));
        emitter.onTimeout(() -> {
            log.warn("[sse] timeout merchantId={} sessionId={}", merchantId, sessionId);
            emitter.complete(); // 超时也显式完成，释放资源
        });
        emitter.onError((ex) -> {
            log.warn("[sse] error merchantId={} sessionId={} err={}", merchantId, sessionId,
                    ex != null ? ex.getMessage() : "null");
            emitter.completeWithError(ex != null ? ex : new RuntimeException("sse-error"));
        });
    }

    /** Step 4 流式主流程 */
    private void streamFlow(SseEmitter emitter, String sessionId, String merchantId, String message) {
        long start = System.nanoTime();
        String usedSupplier = "unknown";
        try {
            // 0) 商家追加转人工词防呆校验
            Set<String> illegal = merchantConfigService.validateHandoffKeywords(
                    merchantId, merchantConfigService.getHandoffKeywords(merchantId));
            if (!illegal.isEmpty()) {
                log.warn("[guard] illegal handoff keywords merchantId={} illegal={}", merchantId, illegal);
            }

            // 1) 意图识别
            IntentResult intent = intentService.recognize(merchantId, message);
            // 2) 并行 Tool Use 预取
            List<ToolUseResult> toolResults = prefetchService.prefetch(merchantId, intent, message);
            // 2.5) Tool Use 耗时埋点（异步，不阻塞）
            for (ToolUseResult t : toolResults) {
                metricsCollector.recordToolUseLatency(merchantId, sessionId, t.intent().name(),
                        t.elapsedNanos(), t.degraded());
            }
            // 3) RAG 静态层检索
            RagRetrieveResult ragResult = ragService.retrieve(merchantId, message, ragTopK);
            metricsCollector.recordRagHit(merchantId, sessionId,
                    ragResult.degraded() ? 0 : ragResult.chunks().size(), ragResult.degraded());
            // 3.5) 加载多轮历史上下文（联合 Key 隔离；失败/过期静默降级单轮）
            List<ChatTurn> history = sessionContextService.loadHistory(merchantId, sessionId);
            // 4) 两层 Prompt 组装（历史上下文注入系统规则层之后、业务层之前）
            String prompt = promptAssembler.assemble(merchantId, message, history, ragResult, toolResults);

            // 5) LLM 流式生成 + 第二层生成中拦截
            StringBuilder fullText = new StringBuilder();
            String scanTail = ""; // 局部缓冲，避免 scanner 单例被多会话串扰（缺陷 B 修复）

            try {
                var chunks = llmRouter.stream(prompt);
                while (chunks.hasNext()) {
                    StreamChunk chunk = chunks.next();
                    usedSupplier = chunk.supplier();
                    if (chunk.done()) {
                        break;
                    }
                    // === 第二层拦截：增量 chunk 扫描（无状态，传入上一次尾部）===
                    String hit = layer2Scanner.scan(scanTail, chunk.text());
                    scanTail = layer2Scanner.tailOf(scanTail, chunk.text());
                    if (hit != null) {
                        metricsCollector.recordLayer2Violation(merchantId, sessionId, hit);
                        // 立即截断：停止后续推送，发降级事件，关闭流
                        safeSend(emitter, SseEvent.degrade(
                                "该问题需人工客服为您处理，正在为您转接。", hit));
                        safeSend(emitter, SseEvent.done());
                        emitter.complete();
                        return;
                    }
                    // 正常推送该 chunk
                    fullText.append(chunk.text());
                    safeSend(emitter, SseEvent.message(chunk.text(), "llm-stream"));
                }
            } catch (LlmClientRouter.LlmUnavailableException llmFail) {
                // 主备皆不可用 → 降级转人工（不卡死）
                sessionContextService.markHandoff(merchantId, sessionId);
                safeSend(emitter, SseEvent.degrade("AI 服务暂不可用，已为您转接人工客服。", "llm-unavailable"));
                safeSend(emitter, SseEvent.done());
                emitter.complete();
                return;
            }

            // 6) 第三层终检（流已发出，发现问题发 revoke + 严重告警）
            String evidence = layer3Inspector.inspect(fullText.toString());
            if (evidence != null) {
                metricsCollector.recordLayer3Violation(merchantId, sessionId, evidence);
                safeSend(emitter, SseEvent.revoke(
                        "【系统提示】刚才的回复存在合规风险，已记录并转人工复核，请稍候。", evidence));
            }

            // 7) 落盘本轮对话（异步语义由 SessionContextService 实现保证；失败静默降级）
            sessionContextService.appendTurn(merchantId, sessionId,
                    new ChatTurn(message, fullText.toString(), System.currentTimeMillis()));

            safeSend(emitter, SseEvent.done());
            emitter.complete();

            long costMs = (System.nanoTime() - start) / 1_000_000;
            log.info("[chat] step5 stream done merchantId={} supplier={} intent={} history={} len={} costMs={}",
                    merchantId, usedSupplier, intent.intent(), history.size(), fullText.length(), costMs);
        } catch (Exception e) {
            log.error("[chat] stream flow error merchantId={}", merchantId, e);
            safeSend(emitter, SseEvent.error("INTERNAL_ERROR", "服务异常，已转人工"));
            emitter.complete();
        }
    }

    /**
     * 安全发送 SSE 事件：客户端中途断开（IllegalStateException / 连接已关闭）属正常，
     * 静默忽略，避免误走"服务异常"分支与错误日志噪声（缺陷 A 修复）。
     */
    private void safeSend(SseEmitter emitter, SseEvent event) {
        try {
            emitter.send(event);
        } catch (IllegalStateException ex) {
            // 连接已关闭（客户端断开），正常，忽略
            log.debug("[sse] send skipped, connection closed: {}", ex.getMessage());
        } catch (IOException ex) {
            log.debug("[sse] send io error (client gone): {}", ex.getMessage());
        }
    }

    /** 健康检查（不经过拦截器路径，独立暴露避免被误拦截） */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }

    /** 简单异步执行包装（避免引入额外线程池 Bean，复用公共 ForkJoinPool Common） */
    private static final class CompletableFutureRunner {
        static void run(Runnable task) {
            java.util.concurrent.CompletableFuture.runAsync(task);
        }
    }
}
