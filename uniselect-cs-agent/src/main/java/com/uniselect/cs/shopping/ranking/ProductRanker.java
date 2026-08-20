package com.uniselect.cs.shopping.ranking;

import com.uniselect.cs.shopping.model.ProductCandidate;
import com.uniselect.cs.shopping.model.RankedProduct;
import com.uniselect.cs.shopping.model.UserContext;

import java.util.List;

/**
 * 四维加权排序接口。
 *
 * <p>对召回候选做最终排序：四维（相关性 / 价格优势 / 活动力度 / 库存紧张度）先各自<b>归一化</b>
 * 到 0~1，再按权重（默认 0.4/0.2/0.2/0.2）加权求和，并施加<b>多样性控制</b>
 * （同子品类 ≤ 半数），输出 Top-N 推荐商品（含理由）。</p>
 */
public interface ProductRanker {

    /**
     * 排序并返回 Top-N。
     *
     * @param candidates 已过滤的召回候选（召回序）
     * @param userCtx    用户上下文（偏好/预算）
     * @return 四维加权排序后的 Top-N 推荐（含综合分与理由），空列表时上层发 degrade
     */
    List<RankedProduct> rank(List<ProductCandidate> candidates, UserContext userCtx);
}
