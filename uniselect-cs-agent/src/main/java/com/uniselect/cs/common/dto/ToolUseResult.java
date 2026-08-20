package com.uniselect.cs.common.dto;

/**
 * Tool Use（动态层实时查询）单次结果。
 *
 * @param intent       对应的意图（决定查询哪种动态字段）
 * @param ok           是否成功取到实时值
 * @param data         实时数据（成功时）；失败/降级时为降级话术
 * @param degraded     是否走降级（超时/失败/不可查）
 * @param queryTimeMs  查询耗时（毫秒），注入上下文时附"查询时间"（评审建议 3.3）
 * @param elapsedNanos 整体耗时（纳秒），用于埋点 ToolUse 耗时
 */
public record ToolUseResult(IntentType intent, boolean ok, String data,
                            boolean degraded, long queryTimeMs, long elapsedNanos) {

    public static ToolUseResult success(IntentType intent, String data, long queryTimeMs, long elapsedNanos) {
        return new ToolUseResult(intent, true, data, false, queryTimeMs, elapsedNanos);
    }

    /** 降级：返回"暂不可查"类话术，不返回旧值（评审建议 3.3 / 动态知识库 5.2） */
    public static ToolUseResult degraded(IntentType intent, String degradeMsg, long elapsedNanos) {
        return new ToolUseResult(intent, false, degradeMsg, true, -1, elapsedNanos);
    }
}
