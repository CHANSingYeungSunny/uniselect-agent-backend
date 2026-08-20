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

    // 导购埋点：event_id 幂等去重（ConcurrentHashMap 天然 putIfAbsent 语义）
    private final java.util.Set<String> recommendEventIds
            = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicLong recommendImpressionCount = new AtomicLong();
    private final AtomicLong recommendClickCount = new AtomicLong();
    private final AtomicLong recommendAddCartCount = new AtomicLong();
    private final AtomicLong recommendOrderCount = new AtomicLong();

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

    // ==================== 导购推荐埋点（event_id 幂等去重） ====================

    @Async("metricsExecutor")
    @Override
    public void recordRecommendImpression(String merchantId, String sessionId, String skuId, String eventId) {
        if (!dedupEvent(eventId)) {
            return; // 幂等：重复 event_id 丢弃，不计次
        }
        long c = recommendImpressionCount.incrementAndGet();
        log.info("[metric] recommend_impression count={} merchantId={} sessionId={} skuId={} eventId={}",
                c, merchantId, sessionId, skuId, eventId);
    }

    @Async("metricsExecutor")
    @Override
    public void recordRecommendClick(String merchantId, String sessionId, String skuId, String eventId) {
        if (!dedupEvent(eventId)) {
            return;
        }
        long c = recommendClickCount.incrementAndGet();
        log.info("[metric] recommend_click count={} merchantId={} sessionId={} skuId={} eventId={}",
                c, merchantId, sessionId, skuId, eventId);
    }

    @Async("metricsExecutor")
    @Override
    public void recordRecommendAddCart(String merchantId, String sessionId, String skuId, String eventId) {
        if (!dedupEvent(eventId)) {
            return;
        }
        long c = recommendAddCartCount.incrementAndGet();
        log.info("[metric] recommend_add_cart count={} merchantId={} sessionId={} skuId={} eventId={}",
                c, merchantId, sessionId, skuId, eventId);
    }

    @Async("metricsExecutor")
    @Override
    public void recordRecommendOrder(String merchantId, String sessionId, String skuId, String eventId) {
        if (!dedupEvent(eventId)) {
            return;
        }
        long c = recommendOrderCount.incrementAndGet();
        log.info("[metric] recommend_order count={} merchantId={} sessionId={} skuId={} eventId={}",
                c, merchantId, sessionId, skuId, eventId);
    }

    /** event_id 幂等：首次出现返回 true 并登记，重复返回 false */
    private boolean dedupEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            // 无 event_id 视为非幂等来源，放行（真实接入应强制要求 event_id）
            return true;
        }
        return recommendEventIds.add(eventId);
    }

    /** 观察点：导购某类埋点已被幂等去重后的净计次（测试用） */
    @Override
    public long observeRecommendCount(String type) {
        return switch (type) {
            case "impression" -> recommendImpressionCount.get();
            case "click" -> recommendClickCount.get();
            case "add_cart" -> recommendAddCartCount.get();
            case "order" -> recommendOrderCount.get();
            default -> 0L;
        };
    }
}
