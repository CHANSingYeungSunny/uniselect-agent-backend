package com.uniselect.cs.common.dto;

/**
 * RAG 检索返回的单个知识片段。
 *
 * @param merchantId 所属商家（命名空间，隔离键）
 * @param docId      文档 ID（如 kb-{merchantId}-product）
 * @param content    片段文本（静态层：规格/政策/FAQ，不含库存/价格）
 * @param score      相似度分数 0~1
 */
public record RagChunk(String merchantId, String docId, String content, double score) {
}
