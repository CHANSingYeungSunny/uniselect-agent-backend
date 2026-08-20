package com.uniselect.cs.common.util;

import com.uniselect.cs.common.constant.SystemRuleConstants;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Prompt 注入统一检测（网关前置预判与 Mock LLM 兜底共用同一规则源）。
 *
 * <p><b>重要</b>：注入检测只应对「用户输入原文」执行，绝不能对组装后的完整 prompt 执行——
 * 系统规则层 Prompt 文本本身包含「忽略…要求…规则」「不得覆盖、降级或绕过」
 * 「不得泄露其他商家的信息」等字样，对完整 prompt 做子串/正则匹配会全量误命中。</p>
 *
 * <p>检测顺序：先精确子串（词表），再正则（变形），命中即视为注入。</p>
 */
public final class PromptInjectionGuard {

    private static final List<Pattern> COMPILED_INJECTION_PATTERNS =
            SystemRuleConstants.PROMPT_INJECTION_PATTERNS.stream()
                    .map(Pattern::compile)
                    .collect(Collectors.toList());

    private PromptInjectionGuard() {
    }

    /**
     * 对指定文本执行注入检测。
     *
     * @param text 待检测文本（必须是用户输入原文，不得是完整 prompt）
     * @return 命中来源（KEYWORD:xxx / PATTERN:xxx）；未命中返回 {@code null}
     */
    public static String match(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String keyword : SystemRuleConstants.PROMPT_INJECTION_KEYWORDS) {
            if (text.contains(keyword)) {
                return "KEYWORD:" + keyword;
            }
        }
        for (Pattern pattern : COMPILED_INJECTION_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return "PATTERN:" + pattern.pattern();
            }
        }
        return null;
    }
}
