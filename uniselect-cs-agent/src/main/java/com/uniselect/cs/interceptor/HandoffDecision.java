package com.uniselect.cs.interceptor;

/**
 * 前置转人工预判结果。
 *
 * @param hit         是否命中转人工触发词
 * @param matchedWord 命中的词（词表精确命中）或首个命中正则的 pattern（便于埋点/审计）
 * @param elapsedNanos 本predict 耗时（纳秒），用于 Inspector 验证 <10ms 红线
 */
public record HandoffDecision(boolean hit, String matchedWord, long elapsedNanos) {

    public static HandoffDecision miss(long elapsedNanos) {
        return new HandoffDecision(false, null, elapsedNanos);
    }

    public static HandoffDecision hit(String matchedWord, long elapsedNanos) {
        return new HandoffDecision(true, matchedWord, elapsedNanos);
    }
}
