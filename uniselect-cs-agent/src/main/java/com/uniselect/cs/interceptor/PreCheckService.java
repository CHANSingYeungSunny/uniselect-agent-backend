package com.uniselect.cs.interceptor;

/**
 * 前置检查契约（拦截器依赖）。
 *
 * <p>设计为接口，便于后续 Step 接入 DB / 配置中心时替换实现，不改动拦截器本身。</p>
 */
public interface PreCheckService {

    /**
     * 校验 merchant_id：非空 + 格式合法。<b>严禁查库 / 调远程</b>，必须在 < 50ms 完成。
     *
     * @return true 表示通过（可进入后续链路）
     */
    boolean validateMerchantId(String merchantId);

    /**
     * 前置转人工预判：平台默认词表 + 正则 + 商家追加词，确定性匹配，< 10ms。
     * 命中即短路，绝不进 LLM 链路。
     */
    HandoffDecision predictHandoff(String merchantId, String message);

    /**
     * 前置 Prompt 注入预判：词表 + 正则，确定性匹配，< 10ms。
     * 命中即短路（emit {@code event: degrade} 拒绝话术），绝不进 LLM 链路（防注入防线第一层）。
     */
    InjectionDecision predictInjection(String merchantId, String message);
}
