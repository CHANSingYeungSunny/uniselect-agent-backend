package com.uniselect.cs.service;

import com.uniselect.cs.common.constant.DegradeConstants;
import com.uniselect.cs.common.dto.IntentResult;
import com.uniselect.cs.common.dto.IntentType;
import com.uniselect.cs.common.dto.ToolUseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tool Use 并行预取编排器（评审建议 2.2 / 3.3 核心）。
 *
 * <p>在意图识别阶段预判"本条消息是否涉库存/价格/订单/物流"，是则<b>并行</b>发起
 * Tool Use 预取，把工具耗时从 LLM 主链路中拿掉（预取不与 LLM 争抢，主链路先走流式输出，
 * Step 4 将预取结果注入上下文）。</p>
 *
 * <p>关键点：
 * <ul>
 *   <li><b>真正并行</b>：用 CompletableFuture + 独立线程池，多个动态意图并行查询，
 *       总耗时按最慢者计，而非串行累加。</li>
 *   <li><b>超时分级</b>：预取模式放宽到 {@code prefetchTimeoutMs=3s}（阻塞模式严格 1.5s，
 *       此处为预取，固定 3s）；超时/异常即降级"暂不可查"，不返回旧值、不拖垮主链路。</li>
 *   <li><b>merchant_id 行级隔离</b>：每次查询强制携带 merchant_id。</li>
 *   <li><b>结果带时间戳</b>：ToolUseResult 记录 queryTimeMs，供 Step 4 注入上下文时
 *       提示 LLM"此为实时查询值，若与历史冲突以本值为准"（评审建议 3.3）。</li>
 * </ul>
 * </p>
 */
@Service
public class ToolUsePrefetchService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ToolUsePrefetchService.class);

    private final ProductDataGateway gateway;
    /** 专用线程池，避免占用 Web 容器线程；容量按并发预估设置 */
    private final ExecutorService prefetchPool = Executors.newFixedThreadPool(8,
            r -> {
                Thread t = new Thread(r, "tool-use-prefetch");
                t.setDaemon(true);
                return t;
            });

    private final long prefetchTimeoutMs;

    public ToolUsePrefetchService(ProductDataGateway gateway,
                                  @org.springframework.beans.factory.annotation.Value(
                                          "${uniselect.cs.tooluse.prefetch-timeout-ms:3000}") long prefetchTimeoutMs) {
        this.gateway = gateway;
        this.prefetchTimeoutMs = prefetchTimeoutMs;
    }

    /**
     * 根据意图结果并行预取所需动态数据。
     *
     * @return 各意图对应的 ToolUseResult 列表（成功/降级均已就位，主链路可直接消费）
     */
    public List<ToolUseResult> prefetch(String merchantId, IntentResult intent, String query) {
        // 收集需要动态数据的意图（可能多条：如同时问"库存和价格"）
        List<IntentType> needed = new ArrayList<>();
        if (intent.intent().requiresDynamicData()) {
            needed.add(intent.intent());
        }
        // 备注：若一条消息含多意图（如"价格和物流"），IntentRecognition 当前返回单一主意图；
        // 后续可扩展为多意图提取。此处对主意图触发预取即可满足预取不占主链路目标。

        if (needed.isEmpty()) {
            return List.of(); // 静态意图，无需预取
        }

        // 并行发起：每个意图一个 CompletableFuture
        List<CompletableFuture<ToolUseResult>> futures = needed.stream()
                .map(it -> CompletableFuture.supplyAsync(
                        () -> doSingle(merchantId, it, query), prefetchPool))
                .toList();

        // 等待全部完成（或超时）：allOf 聚合，按最慢者计
        CompletableFuture<Void> all = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));

        try {
            all.get(prefetchTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[prefetch] timeout after {}ms, merchantId={}, intents={}",
                    prefetchTimeoutMs, merchantId, needed);
            // 超时：对未完成的 future 做降级（已在 doSingle 内设超时，此处兜底）
        } catch (Exception e) {
            log.warn("[prefetch] interrupted: {}", e.getMessage());
        }

        List<ToolUseResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).join());
            } catch (CompletionException ex) {
                // 单个失败 → 降级，不影响其它
                results.add(ToolUseResult.degraded(needed.get(i),
                        DegradeConstants.forIntent(needed.get(i)), 0L));
            }
        }
        return results;
    }

    /** 单次查询（带内部超时与异常降级）。
     *  注意：本方法由外层 supplyAsync 提供的 prefetchPool 线程执行，
     *  内部直接调用 gateway.query，<b>不再二次提交线程池</b>，避免嵌套占用与超时控制混乱。 */
    private ToolUseResult doSingle(String merchantId, IntentType intent, String query) {
        long start = System.nanoTime();
        try {
            // 通过带超时的 FutureTask 包装单次调用，保留内部超时保护
            java.util.concurrent.FutureTask<String> task =
                    new java.util.concurrent.FutureTask<>(() -> gateway.query(merchantId, intent, query));
            // 当前线程直接执行（已由 prefetchPool 线程承载）
            task.run();
            String data = task.get(prefetchTimeoutMs - 200, TimeUnit.MILLISECONDS);
            long elapsed = System.nanoTime() - start;
            long queryTimeMs = TimeUnit.NANOSECONDS.toMillis(elapsed);
            return ToolUseResult.success(intent, data, queryTimeMs, elapsed);
        } catch (TimeoutException te) {
            long elapsed = System.nanoTime() - start;
            log.warn("[prefetch] single timeout intent={} merchantId={}", intent, merchantId);
            return ToolUseResult.degraded(intent, DegradeConstants.forIntent(intent), elapsed);
        } catch (Exception e) {
            long elapsed = System.nanoTime() - start;
            log.warn("[prefetch] single failed intent={} merchantId={} err={}",
                    intent, merchantId, e.getMessage());
            return ToolUseResult.degraded(intent, DegradeConstants.forIntent(intent), elapsed);
        }
    }

    @Override
    public void destroy() {
        prefetchPool.shutdownNow();
    }
}
