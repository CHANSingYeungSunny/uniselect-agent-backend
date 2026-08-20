package com.uniselect.cs.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE 事件统一封装。
 *
 * <p>type 用于前端区分事件语义：
 * <ul>
 *   <li>{@code handoff}      —— 转人工（前置预判命中或生成后兜底）</li>
 *   <li>{@code message}      —— 正常流式/非流式文本（Step 4 接入 / 导购理由逐字）</li>
 *   <li>{@code product}      —— 导购逐商品推荐（data 携带 RankedProduct 详情）</li>
 *   <li>{@code degrade}      —— 降级话术（Step 2~4 接入 / 导购无候选）</li>
 *   <li>{@code error}        —— 隔离/系统错误</li>
 *   <li>{@code done}         —— 流结束标记</li>
 * </ul>
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SseEvent(
        String type,
        Object data,
        /** 事件产生原因（如命中的转人工词），便于埋点，可不回传前端 */
        String reason
) {
    public static SseEvent handoff(String message, String matchedWord) {
        return new SseEvent("handoff", message, "matched:" + matchedWord);
    }

    public static SseEvent message(String text, String reason) {
        return new SseEvent("message", text, reason);
    }

    /** 第二层拦截命中：降级话术（生成中越权，已截断流） */
    public static SseEvent degrade(String message, String word) {
        return new SseEvent("degrade", message, "violation:" + word);
    }

    /** 第三层终检命中（流已发出）：撤销/告警事件，供前端提示 + 事后审计 */
    public static SseEvent revoke(String message, String evidence) {
        return new SseEvent("revoke", message, "evidence:" + evidence);
    }

    public static SseEvent error(String code, String message) {
        return new SseEvent("error", message, code);
    }

    public static SseEvent done() {
        return new SseEvent("done", null, null);
    }

    /**
     * 导购逐商品推荐事件（data 携带商品详情，便于前端渲染卡片）。
     *
     * @param product 排序后的推荐商品（含 reason 理由）
     * @param rank    该商品在 Top-N 中的名次（从 1 开始）
     */
    public static SseEvent product(Object product, int rank) {
        return new SseEvent("product", product, "rank:" + rank);
    }
}
