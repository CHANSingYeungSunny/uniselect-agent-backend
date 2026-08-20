package com.uniselect.cs.service;

import java.util.Set;

/**
 * 商家业务层配置读取接口（系统规则层之外的商家可改部分）。
 *
 * <p>Step 1 为 Mock 实现（内存 Map）；Step 3 接入 DB 时也复用此接口，实现可替换，
 * 拦截器与 PreCheckService 不感知具体实现。</p>
 *
 * <p>仅承载商家可改范围（见《客服 Agent 方案》十章）：追加转人工词、商家业务层 Prompt
 * （卖点/口径/话术）。<b>任何商家内容不得覆盖、降级或绕过系统规则层。</b></p>
 */
public interface MerchantConfigService {

    /**
     * 获取该商家的追加转人工触发词（不含平台默认词，默认词由系统规则层兜底）。
     */
    Set<String> getHandoffKeywords(String merchantId);

    /**
     * 获取商家业务层 Prompt（卖点 / 口径 / 话术），未配置返回空串。
     * 该内容将被拼接在系统规则层之后、用户输入之前，且不得使系统规则层失效。
     */
    String getBusinessPrompt(String merchantId);

    /**
     * 商家是否启用追加转人工词。
     */
    default boolean isExtraHandoffEnabled(String merchantId) {
        return !getHandoffKeywords(merchantId).isEmpty();
    }

    /**
     * 防呆校验（评审建议六）：商家追加转人工词不得包含平台默认词、通用词（如"客服"），
     * 否则所有对话都会转人工。返回非法词集合（空表示通过）。
     */
    Set<String> validateHandoffKeywords(String merchantId, Set<String> candidateKeywords);
}
