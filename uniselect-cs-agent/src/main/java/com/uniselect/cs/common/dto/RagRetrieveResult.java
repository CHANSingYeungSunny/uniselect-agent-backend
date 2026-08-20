package com.uniselect.cs.common.dto;

import java.util.List;

/**
 * RAG 检索整体结果（含降级标记）。
 *
 * @param chunks   命中的知识片段（按相似度降序，最多 topK）
 * @param degraded true 表示本次检索超时/失败降级（非"无命中"），需向 LLM/前端标注"知识库未就绪"
 * @param reason   降级原因（如 TIMEOUT / ERROR），正常检索为空
 */
public record RagRetrieveResult(List<RagChunk> chunks, boolean degraded, String reason) {

    public static RagRetrieveResult hit(List<RagChunk> chunks) {
        return new RagRetrieveResult(chunks, false, null);
    }

    public static RagRetrieveResult empty() {
        return new RagRetrieveResult(List.of(), false, null);
    }

    public static RagRetrieveResult degraded(String reason) {
        return new RagRetrieveResult(List.of(), true, reason);
    }
}
