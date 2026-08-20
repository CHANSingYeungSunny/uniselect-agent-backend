package com.uniselect.cs.shopping.model;

/**
 * 排序后的推荐商品（四维加权排序输出单元）。
 *
 * <p>在 {@link ProductCandidate} 基础上补充：
 * <ul>
 *   <li>{@code score} —— 四维加权综合分（0~1，归一化后加权）；</li>
 *   <li>{@code reason} —— 推荐理由文案（逐商品下发给前端，SSE product 事件携带）。</li>
 * </ul>
 * </p>
 */
public class RankedProduct extends ProductCandidate {

    /** 四维加权综合分（0~1） */
    private final double rankScore;
    /** 推荐理由（可解释性） */
    private final String reason;

    public RankedProduct(ProductCandidate candidate, double rankScore, String reason) {
        super(candidate.skuId(), candidate.merchantId(), candidate.name(), candidate.categoryPath(),
                candidate.price(), candidate.cost(), candidate.inventory(), candidate.status(),
                candidate.promotion(), candidate.promotionDiscount(), candidate.score());
        this.rankScore = rankScore;
        this.reason = reason;
    }

    public double rankScore() {
        return rankScore;
    }

    public String reason() {
        return reason;
    }
}
