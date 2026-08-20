package com.uniselect.cs.service;

import com.uniselect.cs.common.constant.SystemRuleConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 第三层拦截：输出终检（评审建议 2.3）。
 *
 * <p>流结束后对<b>完整文本</b>做最终正则 + 词表校验。注意流式特殊性：
 * 此时内容<b>已发给用户</b>，终检若发现问题，<b>不能"不发送"</b>，而是返回一个 revoke/告警
 * 标记，由调用方发送特殊 SSE 事件（{@code event: revoke}）并记严重告警埋点，供事后审计。</p>
 */
@Service
public class FinalInspector {

    private static final Logger log = LoggerFactory.getLogger(FinalInspector.class);

    /** 终检词表（比生成中更全，含越权+敏感） */
    private static final Set<String> FINAL_VIOLATION_WORDS = Set.of(
            "赔偿", "退款金额", "保证到货", "百分百有效", "一定到货", "绝对退款",
            "加微信", "加我微信", "转账给我", "私下交易"
    );

    /** 终检正则（兜底模糊表述） */
    private static final java.util.List<Pattern> FINAL_PATTERNS = java.util.List.of(
            Pattern.compile("包.*赔"),
            Pattern.compile("肯定.*到货"),
            Pattern.compile("随时.*退款")
    );

    /**
     * 终检完整文本。
     *
     * @return 命中则返回命中证据（词或正则），未命中返回 null
     */
    public String inspect(String fullText) {
        if (fullText == null || fullText.isBlank()) {
            return null;
        }
        for (String word : FINAL_VIOLATION_WORDS) {
            if (fullText.contains(word)) {
                log.warn("[layer3] final violation hit: {}", word);
                return "WORD:" + word;
            }
        }
        for (Pattern p : FINAL_PATTERNS) {
            if (p.matcher(fullText).find()) {
                log.warn("[layer3] final violation hit pattern: {}", p.pattern());
                return "PATTERN:" + p.pattern();
            }
        }
        return null;
    }
}
