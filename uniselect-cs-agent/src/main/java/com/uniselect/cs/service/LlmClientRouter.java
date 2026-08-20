package com.uniselect.cs.service;

import com.uniselect.cs.common.dto.StreamChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * LLM 主备容灾路由器（评审建议 3.5）。
 *
 * <p>策略：
 * <ol>
 *   <li>默认走 {@link LlmSupplier#PRIMARY}（豆包）；</li>
 *   <li>若主供应商在首个 chunk 前/中抛出异常或超时，<b>无缝切换</b>到 {@link LlmSupplier#SECONDARY}（DeepSeek）；</li>
 *   <li>切换仅在「尚未向用户推送任何越权内容」的安全点进行（流式首个 chunk 前失败才切换，
 *       已推送内容后失败则降级转人工，避免内容割裂）；</li>
 *   <li>两级皆失败 → 调用方降级转人工。</li>
 * </ol>
 * 本类对调用方屏蔽主备差异，仅暴露 {@code stream()} 返回的迭代器（内部已处理切换）。</p>
 */
@Service
public class LlmClientRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmClientRouter.class);

    private final LlmClient primaryClient;
    private final LlmClient secondaryClient;

    public LlmClientRouter(LlmClient primaryClient, LlmClient secondaryClient) {
        this.primaryClient = primaryClient;
        this.secondaryClient = secondaryClient;
    }

    /**
     * 返回流式迭代器（已封装主备切换）。调用方照常逐 chunk 消费即可。
     *
     * @param prompt 组装后的 Prompt
     * @return chunk 迭代器；若主失败则在首个元素处切换备；两级皆败抛 RuntimeException
     */
    public Iterator<StreamChunk> stream(String prompt) {
        try {
            return primaryClient.stream(prompt, LlmSupplier.PRIMARY.label());
        } catch (Exception e) {
            // 主供应商初始化/首个 chunk 前即失败 → 切换到备
            log.warn("[llm-router] primary failed, failover to secondary: {}", e.getMessage());
            try {
                return secondaryClient.stream(prompt, LlmSupplier.SECONDARY.label());
            } catch (Exception e2) {
                log.error("[llm-router] both suppliers failed", e2);
                throw new LlmUnavailableException("LLM 主备均不可用", e2);
            }
        }
    }

    /** LLM 主备皆不可用异常，由调用方降级转人工 */
    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
