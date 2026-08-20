package com.uniselect.cs.common.dto;

/**
 * 意图类型枚举。
 * <p>对应《客服 Agent 方案》F1 意图识别。高频售前/售后意图用规则直判；
 * 涉动态层字段（库存/价格/订单/物流）的意图将触发 Tool Use 并行预取。</p>
 */
public enum IntentType {
    // —— 动态层（需实时查询，触发 Tool Use 预取）——
    STOCK("库存查询"),
    PRICE("价格查询"),
    ORDER("订单状态"),
    LOGISTICS("物流轨迹"),

    // —— 静态层（走 RAG，Step 3 接入）——
    SPEC("商品规格"),
    POLICY("售后政策"),
    SHIPPING("运费规则"),
    ACTIVITY("活动优惠"),

    // —— 其它 ——
    GREETING("问候"),
    UNKNOWN("未知/模糊");

    private final String label;

    IntentType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 是否触发动态层 Tool Use 预取 */
    public boolean requiresDynamicData() {
        return this == STOCK || this == PRICE || this == ORDER || this == LOGISTICS;
    }
}
