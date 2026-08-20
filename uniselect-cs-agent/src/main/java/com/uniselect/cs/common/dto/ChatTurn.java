package com.uniselect.cs.common.dto;

/**
 * 单轮对话（用户 + 助手）。
 *
 * @param user       用户本轮输入（实际存储建议脱敏订单号/手机号，Step 5 仅做基础占位）
 * @param assistant  助手本轮回复（截断后存储）
 * @param timestamp  该轮产生时间（毫秒）
 */
public record ChatTurn(String user, String assistant, long timestamp) {
}
