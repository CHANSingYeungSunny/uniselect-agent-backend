package com.uniselect.cs.aspect;

/**
 * 统一埋点接口（Step 1 占位，Step 5 异步化落地）。
 *
 * <p>《客服 Agent 方案》十一章与《评审建议》要求埋点字段包含：
 * 转人工触发、merchant_id 拒绝、意图、RAG 命中、ToolUse 耗时、三层拦截等。</p>
 *
 * <p><b>异步语义</b>：所有实现必须异步执行（独立线程池），调用方（含 SSE 推送 while 循环）
 * 不得被埋点阻塞。具体实现用 {@code @Async("metricsExecutor")} 标注。</p>
 */
public interface MetricsCollector {

    /** 前置转人工预判命中（含命中词来源：DEFAULT/MERCHANT/PATTERN） */
    void recordHandoffTriggered(String merchantId, String sessionId, String source, long elapsedNanos);

    /** 前置 Prompt 注入预判命中（防注入防线第一层短路，source 形如 KEYWORD:xxx / PATTERN:xxx） */
    void recordInjectionBlocked(String merchantId, String sessionId, String source, long elapsedNanos);

    /** merchant_id 隔离拒绝（越权尝试计数） */
    void recordMerchantRejected(String merchantId, String reason);

    /** 正常进入业务链路 */
    void recordPassedGateway(String merchantId, long elapsedNanos);

    /** 第二层拦截命中（生成中越权词，已截断流并降级） */
    void recordLayer2Violation(String merchantId, String sessionId, String word);

    /** 第三层终检命中（流已发出，发 revoke 告警事件 + 严重审计埋点） */
    void recordLayer3Violation(String merchantId, String sessionId, String evidence);

    /** RAG 命中（静态层命中条数，评估检索覆盖率） */
    void recordRagHit(String merchantId, String sessionId, int hitCount, boolean degraded);

    /** Tool Use 实时查询耗时（动态层可用性/降级率评估） */
    void recordToolUseLatency(String merchantId, String sessionId, String intent, long elapsedNanos, boolean degraded);

    /**
     * 观察点（测试/监控用）：最近一次 {@link #recordLayer2Violation} 埋点的<b>真实执行线程名</b>。
     * 用于回归锁定"埋点必须落在独立异步线程池"红线；从未执行过时返回 {@code null}。
     * 真实实现可接入监控系统后返回监控值（如线程池指标），Mock 实现直接返回记录值。
     */
    default String observeLastLayer2Thread() {
        return null;
    }
}
