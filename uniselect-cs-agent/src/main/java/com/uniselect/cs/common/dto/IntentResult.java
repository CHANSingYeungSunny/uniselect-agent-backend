package com.uniselect.cs.common.dto;

/**
 * 意图识别结果。
 *
 * @param intent       识别出的意图类型
 * @param source       识别来源：RULE（规则直判）/ SMALL_MODEL（小模型兜底）
 * @param confidence   置信度 0~1（小模型返回，规则直判为 1.0）
 * @param elapsedNanos 识别耗时（纳秒），用于 Inspector 验证 <200ms 红线
 */
public record IntentResult(IntentType intent, String source, double confidence, long elapsedNanos) {

    public static IntentResult rule(IntentType intent, long elapsedNanos) {
        return new IntentResult(intent, "RULE", 1.0, elapsedNanos);
    }

    public static IntentResult smallModel(IntentType intent, double confidence, long elapsedNanos) {
        return new IntentResult(intent, "SMALL_MODEL", confidence, elapsedNanos);
    }
}
