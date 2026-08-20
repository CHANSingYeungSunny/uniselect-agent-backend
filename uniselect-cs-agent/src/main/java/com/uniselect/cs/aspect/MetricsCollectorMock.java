package com.uniselect.cs.aspect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MetricsCollector 的 Mock 实现（Step 5 异步化）。
 *
 * <p>所有方法 {@code @Async("metricsExecutor")} 在独立线程池执行，
 * <b>绝不阻塞 SSE 流式主链路</b>。当前仍打印结构化日志 + 本地计数器，便于验证埋点触发。</p>
 */
@Service
@Profile("mock")
public class MetricsCollectorMock implements MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollectorMock.class);

    private final AtomicLong handoffCount = new AtomicLong();
    private final AtomicLong injectionCount = new AtomicLong();
    private final AtomicLong rejectCount = new AtomicLong();
    private final AtomicLong passedCount = new AtomicLong();
    private final AtomicLong layer2Count = new AtomicLong();
    private final AtomicLong layer3Count = new AtomicLong();
    private final AtomicLong ragCount = new AtomicLong();
    private final AtomicLong toolUseCount = new AtomicLong();
    private final AtomicReference<String> lastLayer2ThreadName = new AtomicReference<>();

    @Async("metricsExecutor")
    @Override
    public void recordHandoffTriggered(String merchantId, String sessionId, String source, long elapsedNanos) {
        long c = handoffCount.incrementAndGet();
        log.info("[metric] handoff_triggered count={} merchantId={} sessionId={} source={} costMs={}",
                c, merchantId, sessionId, source, elapsedNanos / 1_000_000.0);
    }

    @Async("metricsExecutor")
    @Override
    public void recordInjectionBlocked(String merchantId, String sessionId, String source, long elapsedNanos) {
        long c = injectionCount.incrementAndGet();
        log.warn("[metric][AUDIT] prompt_injection_blocked count={} merchantId={} sessionId={} source={} costMs={}",
                c, merchantId, sessionId, source, elapsedNanos / 1_000_000.0);
    }

    @Async("metricsExecutor")
    @Override
    public void recordMerchantRejected(String merchantId, String reason) {
        long c = rejectCount.incrementAndGet();
        log.warn("[metric] merchant_rejected count={} merchantId={} reason={}", c, merchantId, reason);
    }

    @Async("metricsExecutor")
    @Override
    public void recordPassedGateway(String merchantId, long elapsedNanos) {
        long c = passedCount.incrementAndGet();
        log.debug("[metric] gateway_passed count={} merchantId={} costMs={}",
                c, merchantId, elapsedNanos / 1_000_000.0);
    }

    @Async("metricsExecutor")
    @Override
    public void recordLayer2Violation(String merchantId, String sessionId, String word) {
        lastLayer2ThreadName.set(Thread.currentThread().getName());
        long c = layer2Count.incrementAndGet();
        log.warn("[metric][AUDIT] layer2_violation count={} merchantId={} sessionId={} word={}",
                c, merchantId, sessionId, word);
    }

    @Override
    public String observeLastLayer2Thread() {
        return lastLayer2ThreadName.get();
    }

    @Async("metricsExecutor")
    @Override
    public void recordLayer3Violation(String merchantId, String sessionId, String evidence) {
        long c = layer3Count.incrementAndGet();
        // 严重告警：流已发出，需人工审计复核
        log.error("[metric][AUDIT-SEVERE] layer3_violation count={} merchantId={} sessionId={} evidence={}",
                c, merchantId, sessionId, evidence);
    }

    @Async("metricsExecutor")
    @Override
    public void recordRagHit(String merchantId, String sessionId, int hitCount, boolean degraded) {
        long c = ragCount.incrementAndGet();
        log.debug("[metric] rag_hit count={} merchantId={} sessionId={} hits={} degraded={}",
                c, merchantId, sessionId, hitCount, degraded);
    }

    @Async("metricsExecutor")
    @Override
    public void recordToolUseLatency(String merchantId, String sessionId, String intent,
                                     long elapsedNanos, boolean degraded) {
        long c = toolUseCount.incrementAndGet();
        log.info("[metric] tooluse_latency count={} merchantId={} sessionId={} intent={} costMs={} degraded={}",
                c, merchantId, sessionId, intent, elapsedNanos / 1_000_000.0, degraded);
    }
}
