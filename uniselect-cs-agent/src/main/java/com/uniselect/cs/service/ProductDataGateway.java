package com.uniselect.cs.service;

import com.uniselect.cs.common.dto.IntentType;

/**
 * 统一商品数据网关（评审建议 3.3）。
 *
 * <p>把库存/价格/订单/物流 API 封装为一个内部服务，统一鉴权、限流、熔断、Mock 化，
 * 避免 LLM 链路直连多个上游系统。所有查询<b>强制携带 merchant_id</b> 做行级隔离。</p>
 *
 * <p>本接口为阻塞式单查询；并行编排由 {@code ToolUsePrefetchService} 基于 CompletableFuture 调度。
 * 真实实现替换为 HTTP 调用商品/订单系统，接口保持稳定。</p>
 */
public interface ProductDataGateway {

    /**
     * 按意图查询单个动态字段。
     *
     * @param merchantId 商家 ID（行级隔离键，不可为空）
     * @param intent     决定查库存/价格/订单/物流
     * @param query      查询参数（如 SKU、订单号），由意图提取，可空
     * @return 实时值文本（含"查询时间"提示交由编排层注入）
     * @throws RuntimeException 当上游不可用（超时/报错），由编排层捕获并降级
     */
    String query(String merchantId, IntentType intent, String query);
}
