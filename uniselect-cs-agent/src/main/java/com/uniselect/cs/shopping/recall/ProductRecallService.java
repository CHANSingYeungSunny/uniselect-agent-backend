package com.uniselect.cs.shopping.recall;

import com.uniselect.cs.shopping.model.ProductCandidate;
import com.uniselect.cs.shopping.model.UserContext;

import java.util.List;

/**
 * 混合召回引擎接口。
 *
 * <p>职责：三路召回（向量 / 关键词 / 规则候选池）合并，按 {@code sku_id} 去重保高分，
 * 并施加<b>实时过滤</b>（库存>0、上架、价格≤预算、活动有效），全程严格 {@code merchant_id}
 * 命名空间隔离。A/B 商家即使 sku_id 相同也绝不串数据。</p>
 *
 * <p>真实实现将替换为 pgvector 检索 + DB 关键词查询 + 运营规则池，接口保持稳定。</p>
 */
public interface ProductRecallService {

    /**
     * 召回候选商品。
     *
     * @param merchantId 商家标识（隔离命名空间，缺失/非法由网关层拦截）
     * @param userQuery  用户原始查询（用于向量+关键词召回）
     * @param userCtx    用户上下文（预算/偏好）
     * @return 经实时过滤、去重后的候选列表（召回序，未排序）
     */
    List<ProductCandidate> recall(String merchantId, String userQuery, UserContext userCtx);
}
