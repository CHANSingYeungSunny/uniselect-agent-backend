package com.uniselect.cs.shopping.model;

import java.util.List;

/**
 * 用户导购上下文（用于召回过滤与排序偏好）。
 *
 * <p>隔离红线：{@code merchantId} 强制参与所有召回/排序/埋点命名空间，
 * 缺失即视为非法请求（由网关层提前拦截，此处仅作内部一致性保证）。</p>
 */
public class UserContext {

    /** 预算上限（价格 ≤ budget 才可入选；<=0 表示不限预算） */
    private final double budget;
    /** 用户偏好子品类（排序加权参考，可为空） */
    private final List<String> categoryPrefs;
    /** 会话 ID（联合 Key 隔离 + 埋点关联） */
    private final String sessionId;
    /** 所属商家（命名空间隔离） */
    private final String merchantId;

    public UserContext(String merchantId, String sessionId, double budget, List<String> categoryPrefs) {
        this.merchantId = merchantId;
        this.sessionId = sessionId;
        this.budget = budget;
        this.categoryPrefs = categoryPrefs == null ? List.of() : categoryPrefs;
    }

    public double budget() {
        return budget;
    }

    public List<String> categoryPrefs() {
        return categoryPrefs;
    }

    public String sessionId() {
        return sessionId;
    }

    public String merchantId() {
        return merchantId;
    }
}
