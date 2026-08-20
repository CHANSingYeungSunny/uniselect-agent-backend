package com.uniselect.cs.shopping;

import com.uniselect.cs.service.SessionContextService;
import com.uniselect.cs.shopping.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 客服↔导购会话状态机（轻量）。
 *
 * <p>基于现有 {@link SessionContextService} 联合 Key（merchantId:sessionId）承载状态，
 * 与客服多轮上下文共享同一隔离命名空间，零侵入复用基建。</p>
 *
 * <p>迁移条件（纯前端触发语义，规则集中在此便于评审）：
 * <ul>
 *   <li>"还有推荐吗" / "想买" 类表达 → {@code SHOPPING_GUIDE}</li>
 *   <li>"退换货" / "订单问题" → {@code AFTER_SALES}（仍属客服范畴）</li>
 *   <li>命中转人工词 → {@code HANDOFF}</li>
 *   <li>其它客服咨询 → {@code CUSTOMER_SERVICE}（默认）</li>
 * </ul>
 * </p>
 *
 * <p>切换时生成一次 ≤500 字上下文摘要（便于跨态恢复，遵循"不对完整 prompt 做注入匹配"原则，
 * 仅对用户原文摘要）。</p>
 */
@Service
@Profile("mock")
public class SessionStateMachine {

    private static final Logger log = LoggerFactory.getLogger(SessionStateMachine.class);

    /** 状态摘要长度上限（红线：≤500 字） */
    private static final int SUMMARY_MAX_CHARS = 500;

    private final SessionContextService sessionContextService;
    /** merchantId:sessionId -> 当前状态 */
    private final ConcurrentHashMap<String, SessionState> states = new ConcurrentHashMap<>();

    public SessionStateMachine(SessionContextService sessionContextService) {
        this.sessionContextService = sessionContextService;
    }

    /** 读取当前状态（默认客服态） */
    public SessionState currentState(String merchantId, String sessionId) {
        return states.getOrDefault(compositeKey(merchantId, sessionId), SessionState.CUSTOMER_SERVICE);
    }

    /**
     * 依据用户消息推导目标状态并迁移（若发生迁移，生成一次 ≤500 字摘要）。
     *
     * @return 迁移后的状态
     */
    public SessionState transition(String merchantId, String sessionId, String userQuery) {
        SessionState target = decide(userQuery);
        SessionState prev = states.put(compositeKey(merchantId, sessionId), target);
        if (prev != target) {
            String summary = buildSummary(merchantId, sessionId, target);
            log.debug("[state-machine] merchantId={} sessionId={} {} -> {} summaryLen={}",
                    merchantId, sessionId, prev, target, summary.length());
        }
        return target;
    }

    /** 显式设置状态（如状态机外部判定 HANDOFF） */
    public void forceState(String merchantId, String sessionId, SessionState state) {
        states.put(compositeKey(merchantId, sessionId), state);
    }

    /** 迁移条件判定 */
    private SessionState decide(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return SessionState.CUSTOMER_SERVICE;
        }
        String q = userQuery.toLowerCase();
        // 转人工词（与系统规则层默认词对齐，最高优先级）
        for (String kw : new String[]{"人工", "转人工", "投诉", "退款", "退货", "赔偿"}) {
            if (q.contains(kw)) {
                return SessionState.HANDOFF;
            }
        }
        if (q.contains("退换货") || q.contains("订单问题") || q.contains("物流")) {
            return SessionState.AFTER_SALES;
        }
        if (q.contains("推荐") || q.contains("想买") || q.contains("买个") || q.contains("还有") || q.contains("选")) {
            return SessionState.SHOPPING_GUIDE;
        }
        return SessionState.CUSTOMER_SERVICE;
    }

    /** 生成 ≤500 字状态摘要（仅基于状态与目标，不拼接完整 prompt，避免注入误命中） */
    private String buildSummary(String merchantId, String sessionId, SessionState state) {
        String base = switch (state) {
            case SHOPPING_GUIDE -> "用户进入导购推荐态，需基于预算与偏好召回候选并排序输出。";
            case AFTER_SALES -> "用户进入售后态，处理退换货/订单/物流问题。";
            case HANDOFF -> "用户触发转人工，已移交人工客服处理。";
            case CUSTOMER_SERVICE -> "用户在客服态进行常规咨询。";
        };
        // 真实实现应压缩历史轮次；此处仅截断至 500 字（红线）
        return truncate(base, SUMMARY_MAX_CHARS);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String compositeKey(String merchantId, String sessionId) {
        return merchantId + ":" + sessionId;
    }
}
