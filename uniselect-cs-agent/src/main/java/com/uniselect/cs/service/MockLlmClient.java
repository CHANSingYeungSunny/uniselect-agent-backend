package com.uniselect.cs.service;

import com.uniselect.cs.common.dto.StreamChunk;
import com.uniselect.cs.common.util.PromptInjectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Mock LLM 流式客户端（主/备共用实现，按 supplier 区分行为）。
 *
 * <p>模拟：将一段预设回复按 chunk（如每 4 字）切分，每个 chunk 间隔 {@code chunkDelayMs}（默认 50ms），
 * 模拟真实流式首 token 快、后续增量推送。主供应商（doubao）有 {@code primaryFailRate} 概率在
 * 首个 chunk 前抛异常，触发 router 无缝切换备（deepseek）。</p>
 */
@Service
@Profile("mock")
public class MockLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MockLlmClient.class);

    private final long chunkDelayMs;
    private final double primaryFailRate;

    public MockLlmClient(@Value("${uniselect.cs.llm.chunk-delay-ms:50}") long chunkDelayMs,
                         @Value("${uniselect.cs.llm.primary-fail-rate:0.1}") double primaryFailRate) {
        this.chunkDelayMs = chunkDelayMs;
        this.primaryFailRate = primaryFailRate;
    }

    @Override
    public Iterator<StreamChunk> stream(String prompt, String supplier) {
        // 主供应商模拟随机失败（触发 router 切换）
        if (LlmSupplier.PRIMARY.label().equals(supplier)
                && ThreadLocalRandom.current().nextDouble() < primaryFailRate) {
            throw new RuntimeException("PRIMARY_TIMEOUT: doubao first-token timeout");
        }

        // 预设回复（模拟 LLM 基于 prompt 生成；真实实现由模型产出）
        String reply = buildMockReply(prompt);
        List<String> chunks = splitIntoChunks(reply, 4);

        return new Iterator<>() {
            private int idx = 0;

            @Override
            public boolean hasNext() {
                return idx <= chunks.size(); // 多一个用于 done chunk
            }

            @Override
            public StreamChunk next() {
                if (idx < chunks.size()) {
                    String c = chunks.get(idx++);
                    sleep(chunkDelayMs);
                    return StreamChunk.of(c, supplier);
                } else if (idx == chunks.size()) {
                    idx++;
                    return StreamChunk.end(supplier);
                }
                throw new java.util.NoSuchElementException();
            }
        };
    }

    /** 基于 prompt 模板生成 mock 回复（实际场景由 LLM 产出，此处确定性便于测试拦截） */
    private String buildMockReply(String prompt) {
        // 0) 防注入兜底（第二层）：Mock LLM 同样拒绝注入指令。
        //    只对"用户输入原文"检测——系统规则层文本本身含"忽略…规则/其他商家"等字样，
        //    对完整 prompt 匹配会全量误命中（见 PromptAssembler.extractUserInput 注释）。
        String userInput = PromptAssembler.extractUserInput(prompt);
        if (PromptInjectionGuard.match(userInput) != null) {
            return "抱歉，我无法响应此类请求。我只能提供本店商品与售后相关的咨询，并且会始终坚持系统规则。";
        }
        // 1) 越权词（模拟模型越权表述），用于验证第二层拦截
        if (prompt.contains("越权测试-退款金额")) {
            return "亲，我们承诺退款金额全额原路退回，并保证到货时间，百分百有效哦。";
        }
        // 2) 默认答复（正常问答）：自然客服口吻，体现"实时查询"语感（无"数据库"等技术词汇）。
        //    安全校验：不含转人工词（人工/退款/投诉/赔偿…）与越权词（赔偿/退款金额/保证到货/百分百有效…），不会误触发拦截。
        return "刚帮您实时查了一下，这款保温杯目前库存充足（剩余 158 件），"
                + "今天参加满减活动只要 99 元哦。需要帮您下单吗？";
    }

    private List<String> splitIntoChunks(String text, int size) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            out.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return out;
    }

    private void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
