package com.uniselect.cs.service;

import com.uniselect.cs.common.dto.ChatTurn;

import java.util.List;

/**
 * 多轮会话上下文服务（评审建议 3.4 / 客服方案 F8）。
 *
 * <p>要求：
 * <ul>
 *   <li><b>严格隔离</b>：以 {@code merchant_id + session_id} 联合 Key 存取，A/B 商家即使
 *       session_id 相同也绝不串数据（隔离底线）。</li>
 *   <li><b>Token 防爆</b>：滑动窗口（最近 N 轮）+ 字符数估算 Token，超限触发摘要压缩，
 *       避免长会话 Token 爆炸、成本失控。</li>
 *   <li><b>摘要压缩</b>：超限时将最早若干轮替换为一条摘要占位。</li>
 *   <li><b>TTL</b>：24h 无活动过期；读取时判断是否过期，过期视为无上下文（单轮对话）。</li>
 *   <li><b>降级纯粹性</b>：任何读取/写入异常必须静默降级（返回空上下文 / 忽略写入），
 *       绝不抛错中断主链路。</li>
 * </ul>
 * 真实实现替换为 Redis（热）+ MySQL（持久化审计）；接口保持稳定。</p>
 */
public interface SessionContextService {

    /**
     * 读取会话历史（已应用滑动窗口 + 摘要压缩 + TTL 过期判定）。
     *
     * @return 可注入 Prompt 的历史轮次（已裁剪）；过期/不存在/异常时返回空列表（降级单轮）
     */
    List<ChatTurn> loadHistory(String merchantId, String sessionId);

    /**
     * 追加一轮对话（异步落库视角；此处同步写内存，异常静默忽略）。
     * 写入后内部触发窗口裁剪与摘要压缩。
     */
    void appendTurn(String merchantId, String sessionId, ChatTurn turn);

    /**
     * 标记会话已转人工（此后不再恢复 AI 生成，评审建议 3.4）。
     */
    void markHandoff(String merchantId, String sessionId);
}
