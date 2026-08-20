package com.uniselect.cs.common.dto;

/**
 * LLM 流式输出单元。
 *
 * @param text      本 chunk 文本（增量）
 * @param done      是否为最后一个 chunk
 * @param supplier  实际提供该 chunk 的供应商（主/备），用于埋点与审计
 */
public record StreamChunk(String text, boolean done, String supplier) {

    public static StreamChunk of(String text, String supplier) {
        return new StreamChunk(text, false, supplier);
    }

    public static StreamChunk end(String supplier) {
        return new StreamChunk("", true, supplier);
    }
}
