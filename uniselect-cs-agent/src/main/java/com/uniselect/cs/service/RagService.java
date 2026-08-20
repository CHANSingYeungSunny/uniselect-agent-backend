package com.uniselect.cs.service;

import com.uniselect.cs.common.dto.RagChunk;
import com.uniselect.cs.common.dto.RagRetrieveResult;

import java.util.List;

/**
 * RAG 静态层检索服务（评审建议 2.2 / 客服方案 F2）。
 *
 * <p>要求：
 * <ul>
 *   <li><b>merchant_id 命名空间隔离</b>：检索仅限本商家文档，绝不跨店。</li>
 *   <li><b>Top-K=5</b>，超时 <b>100ms</b> 即降级空结果（不拖垮主链路 P95）。</li>
 *   <li>返回静态层内容（规格/政策/FAQ），不含库存/价格等动态字段（由动态层实时取）。</li>
 * </ul>
 * 真实实现替换为 Spring AI + pgvector；接口保持稳定。</p>
 */
public interface RagService {

    /**
     * 检索静态知识。
     *
     * @param merchantId 商家 ID（命名空间隔离键）
     * @param query      用户问题（或意图扩展后的检索串）
     * @param topK       返回条数（固定 5）
     * @return 检索结果（含降级标记，区分"无命中"与"超时降级"）
     */
    RagRetrieveResult retrieve(String merchantId, String query, int topK);
}
