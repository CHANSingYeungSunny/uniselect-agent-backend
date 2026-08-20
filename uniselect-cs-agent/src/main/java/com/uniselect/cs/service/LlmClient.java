package com.uniselect.cs.service;

import com.uniselect.cs.common.dto.StreamChunk;

import java.util.Iterator;

/**
 * LLM 流式接入层（Spring AI 抽象占位）。
 *
 * <p>真实实现替换为 Spring AI 的 {@code StreamingChatModel}（豆包/DeepSeek），
 * 当前 Mock 按 chunk 吐出模拟首 token 快、后续增量推送。主备容灾由 {@code LlmClientRouter} 调度。</p>
 *
 * <p>返回 {@code Iterator<StreamChunk>}，调用方逐 chunk 消费并实时推送 SSE；
 * 不得在客户端内部阻塞等待全量生成。</p>
 */
public interface LlmClient {

    /**
     * 流式生成。
     *
     * @param prompt  已组装的 Prompt（含系统规则层 + 商家业务层 + 用户输入防注入）
     * @param supplier 本次使用的供应商标识（主/备），由 router 传入
     * @return chunk 迭代器（流式）
     */
    Iterator<StreamChunk> stream(String prompt, String supplier);
}
