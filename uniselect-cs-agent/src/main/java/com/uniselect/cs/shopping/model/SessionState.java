package com.uniselect.cs.shopping.model;

/**
 * 客服↔导购会话状态枚举（轻量状态机）。
 *
 * <p>状态迁移（见 {@link com.uniselect.cs.shopping.SessionStateMachine}）：
 * <ul>
 *   <li>{@code CUSTOMER_SERVICE} —— 客服态（默认进入，处理咨询/售前售后问题）</li>
 *   <li>{@code SHOPPING_GUIDE}   —— 导购态（用户表达购买意图，进入推荐链路）</li>
 *   <li>{@code AFTER_SALES}       —— 售后态（退换货/订单问题，仍属客服范畴）</li>
 *   <li>{@code HANDOFF}           —— 人工态（命中转人工词，移交人工客服）</li>
 * </ul>
 * </p>
 */
public enum SessionState {
    CUSTOMER_SERVICE,
    SHOPPING_GUIDE,
    AFTER_SALES,
    HANDOFF
}
