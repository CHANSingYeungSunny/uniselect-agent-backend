package com.uniselect.cs.interceptor;

/**
 * Prompt 注入预判结果（防注入防线第一层：网关前置短路，绝不进 LLM 链路）。
 *
 * @param hit         是否命中注入
 * @param matchedWord 命中的触发词/正则（形如 KEYWORD:xxx / PATTERN:xxx），未命中为 null
 * @param elapsedNanos 预判耗时（纳秒）
 */
public record InjectionDecision(boolean hit, String matchedWord, long elapsedNanos) {

    public static InjectionDecision miss(long elapsedNanos) {
        return new InjectionDecision(false, null, elapsedNanos);
    }

    public static InjectionDecision hit(String matchedWord, long elapsedNanos) {
        return new InjectionDecision(true, matchedWord, elapsedNanos);
    }
}
