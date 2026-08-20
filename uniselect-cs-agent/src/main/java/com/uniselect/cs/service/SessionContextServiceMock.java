package com.uniselect.cs.service;

import com.uniselect.cs.common.dto.ChatTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SessionContextService 的 Mock 实现（内存模拟 Redis）。
 *
 * <p>关键保证：
 * <ul>
 *   <li><b>联合 Key 隔离</b>：内部 Map 的 key 为 {@code merchantId + ":" + sessionId}，
 *       即使两个商家 session_id 相同也分属不同桶，绝不串数据。</li>
 *   <li><b>滑动窗口</b>：保留最近 {@code maxRounds} 轮（默认 8）。</li>
 *   <li><b>Token 防爆 + 摘要压缩</b>：当累计字符（估算 Token）超 {@code maxChars} 时，
 *       将最早 {@code compactRounds} 轮合并为一条摘要占位（"[历史对话摘要：...]"）。</li>
 *   <li><b>TTL 24h</b>：记录最后更新时间，读取时若超过 {@code ttlMs} 视为过期 → 返回空（单轮）。</li>
 *   <li><b>降级纯粹性</b>：所有读写包 try/catch，异常静默降级（不抛主链路）。</li>
 * </ul>
 * </p>
 */
@Service
@Profile("mock")
public class SessionContextServiceMock implements SessionContextService {

    private static final Logger log = LoggerFactory.getLogger(SessionContextServiceMock.class);

    /** 联合 Key -> 会话数据（带 TTL 戳） */
    private final Map<String, SessionData> store = new ConcurrentHashMap<>();

    private final int maxRounds;
    private final int maxChars;
    private final int compactRounds;
    private final long ttlMs;

    public SessionContextServiceMock(
            @Value("${uniselect.cs.context.max-rounds:8}") int maxRounds,
            @Value("${uniselect.cs.context.max-chars:2000}") int maxChars,
            @Value("${uniselect.cs.context.compact-rounds:3}") int compactRounds,
            @Value("${uniselect.cs.context.ttl-ms:86400000}") long ttlMs) {
        this.maxRounds = maxRounds;
        this.maxChars = maxChars;
        this.compactRounds = compactRounds;
        this.ttlMs = ttlMs;
    }

    /** 联合 Key：merchantId 与 sessionId 拼接，确保跨商家隔离 */
    private static String compositeKey(String merchantId, String sessionId) {
        return merchantId + ":" + sessionId;
    }

    @Override
    public List<ChatTurn> loadHistory(String merchantId, String sessionId) {
        try {
            SessionData data = store.get(compositeKey(merchantId, sessionId));
            if (data == null) {
                return List.of();
            }
            // TTL 过期判定：超过 ttlMs 无活动 → 视为无上下文（降级单轮）
            if (System.currentTimeMillis() - data.lastUpdate > ttlMs) {
                log.debug("[context] session expired merchantId={} sessionId={}", merchantId, sessionId);
                return List.of();
            }
            // 复制时加锁，避免与 appendTurn/compact 并发修改
            synchronized (data) {
                return new ArrayList<>(data.turns);
            }
        } catch (Exception e) {
            // 降级纯粹性：读取失败 → 单轮对话
            log.warn("[context] load failed, degrade to single-turn: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void appendTurn(String merchantId, String sessionId, ChatTurn turn) {
        try {
            String key = compositeKey(merchantId, sessionId);
            SessionData data = store.computeIfAbsent(key, k -> new SessionData(new LinkedList<>(), 0L));
            synchronized (data) {
                data.turns.add(turn);
                data.lastUpdate = System.currentTimeMillis();

                // 滑动窗口：超出 maxRounds 丢弃最旧
                while (data.turns.size() > maxRounds) {
                    data.turns.removeFirst();
                }
                // Token 防爆：累计字符超阈值 → 摘要压缩最早 compactRounds 轮
                if (estimateChars(data.turns) > maxChars && data.turns.size() > compactRounds) {
                    compact(data);
                }
            }
        } catch (Exception e) {
            // 降级纯粹性：写入失败静默忽略，不影响主链路
            log.warn("[context] append failed, ignored: {}", e.getMessage());
        }
    }

    @Override
    public void markHandoff(String merchantId, String sessionId) {
        try {
            SessionData data = store.get(compositeKey(merchantId, sessionId));
            if (data != null) {
                synchronized (data) {
                    data.handoff = true;
                }
            }
        } catch (Exception e) {
            log.warn("[context] markHandoff failed, ignored: {}", e.getMessage());
        }
    }

    /** 字符数估算（Mock Token 估算：中文约 1 字≈1 token，英文按词） */
    private int estimateChars(List<ChatTurn> turns) {
        int sum = 0;
        for (ChatTurn t : turns) {
            sum += (t.user() == null ? 0 : t.user().length());
            sum += (t.assistant() == null ? 0 : t.assistant().length());
        }
        return sum;
    }

    /** 摘要压缩：将最早 compactRounds 轮合并为一条摘要占位 */
    private void compact(SessionData data) {
        List<ChatTurn> toCompact = new ArrayList<>();
        for (int i = 0; i < compactRounds && !data.turns.isEmpty(); i++) {
            toCompact.add(data.turns.removeFirst());
        }
        StringBuilder sb = new StringBuilder("[历史对话摘要：");
        for (ChatTurn t : toCompact) {
            sb.append("用户询问了\"").append(truncate(t.user(), 20))
                    .append("\"，客服回答了\"").append(truncate(t.assistant(), 20)).append("\"；");
        }
        sb.append("]");
        // 摘要作为一条特殊轮次放回队首（user 为摘要，assistant 空）
        data.turns.addFirst(new ChatTurn(sb.toString(), "", System.currentTimeMillis()));
        log.debug("[context] compacted {} rounds into summary", toCompact.size());
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 会话数据载体（线程安全：ConcurrentHashMap + LinkedList 单 key 内同步访问） */
    private static final class SessionData {
        final LinkedList<ChatTurn> turns;
        long lastUpdate;
        boolean handoff;

        SessionData(LinkedList<ChatTurn> turns, long lastUpdate) {
            this.turns = turns;
            this.lastUpdate = lastUpdate;
        }
    }
}
