package com.uniselect.cs.service;

import com.uniselect.cs.common.constant.SystemRuleConstants;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MerchantConfigService 的 Mock 实现（内存 Map，按 merchant_id 隔离）。
 *
 * <p>用于 Step 1~4 无 DB 环境下跑通主流程。后续 Step 切换为 DB 实现即可，
 * 拦截器与 PreCheckService 不感知具体实现。</p>
 */
@Service
@Profile("mock")
public class MerchantConfigServiceMock implements MerchantConfigService {

    /** merchant_id -> 追加转人工词集合 */
    private final Map<String, Set<String>> extraHandoffKeywords = new HashMap<>();
    /** merchant_id -> 商家业务层 Prompt */
    private final Map<String, String> businessPrompts = new HashMap<>();

    /** 安全兜底：通用词/禁用词，商家追加转人工词不得包含（防呆，评审建议六） */
    private static final Set<String> FORBIDDEN_HANDOFF_WORDS = Set.of(
            "客服", "人工", "机器人", "智能", "对话", "咨询", "帮助", "服务"
    );

    public MerchantConfigServiceMock() {
        // 示例：M-1001 追加合法转人工词 + 一条业务层 Prompt
        extraHandoffKeywords.put("M-1001", Set.of("发票问题", "发货太慢"));
        businessPrompts.put("M-1001",
                "本店主营保温杯/通勤包，主打轻便防漏。回复请使用亲切口语，一次不超过 3 个要点。");
        // 示例：M-1002 故意含一个会被防呆拦截的坏词（验证校验生效）
        extraHandoffKeywords.put("M-1002", Set.of("物流问题", "客服不理人"));
    }

    @Override
    public Set<String> getHandoffKeywords(String merchantId) {
        return extraHandoffKeywords.getOrDefault(merchantId, Set.of());
    }

    @Override
    public String getBusinessPrompt(String merchantId) {
        return businessPrompts.getOrDefault(merchantId, "");
    }

    @Override
    public Set<String> validateHandoffKeywords(String merchantId, Set<String> candidateKeywords) {
        Set<String> illegal = new HashSet<>();
        for (String kw : candidateKeywords) {
            if (kw == null || kw.isBlank()) {
                illegal.add("EMPTY");
                continue;
            }
            // 1) 不得与平台默认词重复（默认词已强制，追加无意义且易误触）
            if (SystemRuleConstants.DEFAULT_HANDOFF_KEYWORDS.contains(kw)) {
                illegal.add("DEFAULT_DUP:" + kw);
            }
            // 2) 不得包含通用/禁用词（否则全员转人工）
            for (String forbidden : FORBIDDEN_HANDOFF_WORDS) {
                if (kw.contains(forbidden)) {
                    illegal.add("FORBIDDEN:" + kw);
                }
            }
        }
        return illegal;
    }
}
