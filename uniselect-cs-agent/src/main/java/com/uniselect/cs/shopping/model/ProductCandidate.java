package com.uniselect.cs.shopping.model;

/**
 * 召回候选商品（混合召回引擎的输出单元）。
 *
 * <p>字段与 {@code product_catalog} 表、{@code product_embeddings} 表对齐，
 * 真实切换时可直接由 DB 行映射。其中 {@code score} 为召回阶段各路输出的候选分
 * （向量相似度 / 关键词命中分 / 规则优先级），用于合并去重时"保高分"。</p>
 */
public class ProductCandidate {

    /** 商品 SKU（商家内唯一） */
    private final String skuId;
    /** 所属商家（命名空间隔离） */
    private final String merchantId;
    /** 商品名称 */
    private final String name;
    /** 类目路径，形如 "家居/厨房/杯具"，末段为子品类 */
    private final String categoryPath;
    /** 售价 */
    private final double price;
    /** 成本 */
    private final double cost;
    /** 库存（>0 才可推荐） */
    private final int inventory;
    /** 上下架状态：1=上架，0=下架 */
    private final int status;
    /** 是否参加活动 */
    private final boolean promotion;
    /** 活动优惠金额 */
    private final double promotionDiscount;
    /** 召回阶段候选分（用于合并去重保高分） */
    private final double score;

    public ProductCandidate(String skuId, String merchantId, String name, String categoryPath,
                            double price, double cost, int inventory, int status,
                            boolean promotion, double promotionDiscount, double score) {
        this.skuId = skuId;
        this.merchantId = merchantId;
        this.name = name;
        this.categoryPath = categoryPath;
        this.price = price;
        this.cost = cost;
        this.inventory = inventory;
        this.status = status;
        this.promotion = promotion;
        this.promotionDiscount = promotionDiscount;
        this.score = score;
    }

    /** 子品类（类目路径末段），用于多样性控制 */
    public String subCategory() {
        if (categoryPath == null || categoryPath.isEmpty()) {
            return "";
        }
        int idx = categoryPath.lastIndexOf('/');
        return idx >= 0 ? categoryPath.substring(idx + 1) : categoryPath;
    }

    public String skuId() {
        return skuId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String name() {
        return name;
    }

    public String categoryPath() {
        return categoryPath;
    }

    public double price() {
        return price;
    }

    public double cost() {
        return cost;
    }

    public int inventory() {
        return inventory;
    }

    public int status() {
        return status;
    }

    public boolean promotion() {
        return promotion;
    }

    public double promotionDiscount() {
        return promotionDiscount;
    }

    public double score() {
        return score;
    }
}
