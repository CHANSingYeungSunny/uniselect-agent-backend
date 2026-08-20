package com.uniselect.cs.common.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 客服对话入参。
 * 后续 Step 直接复用本结构，不重构无关字段。
 */
public record ChatRequest(
        /** 会话 ID，用于多轮上下文（Step 5 接入） */
        @NotBlank String sessionId,
        /** 商家 ID，行级隔离键，缺失/非法一律拒绝 */
        @NotBlank String merchantId,
        /** 消费者输入（原始文本，拦截器内仅做匹配，不打印原文全量） */
        @NotBlank String message
) {
}
