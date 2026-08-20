package com.uniselect.cs.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 第二层拦截：生成中检查（评审建议 2.3 核心）。
 *
 * <p>对<b>流式增量 chunk</b> 做越权关键词扫描（词表优先：赔偿/退款金额/保证到货/百分百有效）。
 * 命中即标记截断，调用方停止后续 chunk 推送并发送降级事件。</p>
 *
 * <p><b>无状态设计（并发安全）</b>：本 scanner 为单例 Bean，<b>不持有跨请求的缓冲</b>。
 * 为解决越权词被 chunk 边界劈开（如 "退"+"款金额"），扫描需看到「上一 chunk 的尾部」。
 * 因此缓冲由<b>调用方在局部变量中维护</b>，通过 {@link #scan(String, String)} 传入上一次尾部
 * 并返回本次尾部，避免多会话共用同一 scanner 实例导致的串扰。</p>
 */
@Service
public class StreamViolationScanner {

    private static final Logger log = LoggerFactory.getLogger(StreamViolationScanner.class);

    /** 跨 chunk 缓冲长度：取最长越权词长度，避免边界劈词漏检 */
    private static final int BUFFER_LEN = 6;

    /** 越权词表（系统规则层红线，不可被商家覆盖） */
    private static final Set<String> VIOLATION_WORDS = Set.of(
            "赔偿", "退款金额", "保证到货", "百分百有效", "一定到货", "绝对退款"
    );

    /**
     * 扫描一个增量 chunk（无状态）。
     *
     * @param prevTail 上一 chunk 处理后的尾部缓冲（首次传空串）；可为 null
     * @param chunkText 本次增量文本
     * @return 命中则返回命中词（非 null），否则 null
     */
    public String scan(String prevTail, String chunkText) {
        if (chunkText == null || chunkText.isEmpty()) {
            return null;
        }
        StringBuilder buf = new StringBuilder(prevTail == null ? "" : prevTail);
        buf.append(chunkText);
        if (buf.length() > BUFFER_LEN) {
            buf.delete(0, buf.length() - BUFFER_LEN);
        }
        String window = buf.toString();
        for (String word : VIOLATION_WORDS) {
            if (window.contains(word)) {
                log.warn("[layer2] violation hit in stream: {}", word);
                return word;
            }
        }
        // 返回本次尾部供下次拼接（最多 BUFFER_LEN）
        return null;
    }

    /**
     * 计算本次扫描后应保留的尾部缓冲（供调用方在下个 chunk 传入）。
     */
    public String tailOf(String prevTail, String chunkText) {
        StringBuilder buf = new StringBuilder(prevTail == null ? "" : prevTail);
        buf.append(chunkText == null ? "" : chunkText);
        if (buf.length() > BUFFER_LEN) {
            buf.delete(0, buf.length() - BUFFER_LEN);
        }
        return buf.toString();
    }
}
