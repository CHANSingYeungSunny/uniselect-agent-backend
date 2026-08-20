package com.uniselect.cs.service;

import com.uniselect.cs.common.dto.ChatTurn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 红线回归测试集 —— 多轮上下文服务（Step 5 修复的并发/降级缺陷锁定点）。
 *
 * <p>纯单元测试：直接 new {@link SessionContextServiceMock}（默认值 8 轮 / 2000 字符 / 24h TTL），
 * 不启动 Spring 容器，聚焦红线契约。</p>
 */
class SessionContextServiceMockTest {

    /** 默认值构造器（对齐 application.yml 的 uniselect.cs.context.* ） */
    private SessionContextServiceMock newMock() {
        return new SessionContextServiceMock(8, 2000, 3, 86_400_000L);
    }

    private ChatTurn turn(String u, String a) {
        return new ChatTurn(u, a, System.currentTimeMillis());
    }

    // ===== 红线 1：联合 Key 隔离（merchant_id + session_id）=====
    @Test
    void 联合Key隔离_不同商家相同sessionId不串数据() {
        SessionContextService svc = newMock();
        svc.appendTurn("M-1001", "s1", turn("A店问题", "A店答复"));
        svc.appendTurn("M-1002", "s1", turn("B店问题", "B店答复"));

        List<ChatTurn> a = svc.loadHistory("M-1001", "s1");
        List<ChatTurn> b = svc.loadHistory("M-1002", "s1");

        assertEquals(1, a.size());
        assertEquals(1, b.size());
        assertEquals("A店问题", a.get(0).user());
        assertEquals("B店问题", b.get(0).user());
        // 严格断言：A 店数据绝不包含 B 店内容
        assertTrue(a.stream().noneMatch(t -> "B店问题".equals(t.user())));
        assertTrue(b.stream().noneMatch(t -> "A店问题".equals(t.user())));
    }

    // ===== 红线 2：Token 防爆（滑动窗口 + 摘要压缩）=====
    @Test
    void Token防爆_超过滑动窗口轮数后裁剪到上限() {
        SessionContextService svc = newMock();
        for (int i = 0; i < 20; i++) {
            svc.appendTurn("M-1001", "s1", turn("用户第" + i + "轮提问", "客服第" + i + "轮答复"));
        }
        List<ChatTurn> history = svc.loadHistory("M-1001", "s1");
        // 滑动窗口：最多保留 maxRounds=8 轮
        assertTrue(history.size() <= 8, "滑动窗口应裁剪到 <=8 轮，实际=" + history.size());
        // 保留的是最近 8 轮（末尾），验证最后一次"第19轮"在列
        assertTrue(history.stream().anyMatch(t -> "用户第19轮提问".equals(t.user())));
    }

    @Test
    void Token防爆_累计字符超阈值触发摘要压缩() {
        SessionContextService svc = newMock();
        // 每轮约 200 字符，12 轮 ≈ 2400 > maxChars(2000)，应触发摘要压缩
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longText.append("这是一段用于测试token防爆的超长对话内容");
        }
        for (int i = 0; i < 12; i++) {
            svc.appendTurn("M-1001", "s1",
                    turn(longText.toString(), longText.toString()));
        }
        List<ChatTurn> history = svc.loadHistory("M-1001", "s1");
        boolean hasSummary = history.stream()
                .anyMatch(t -> t.user() != null && t.user().startsWith("[历史对话摘要"));
        assertTrue(hasSummary, "超字符阈值应触发摘要压缩，但历史中未发现摘要占位");
    }

    // ===== 红线 5：降级纯粹性（loadHistory 异常 → 空列表，不崩溃）=====
    @Test
    void 降级纯粹性_store读取异常时返回空列表不抛错() throws Exception {
        SessionContextServiceMock svc = newMock();
        // 通过反射将私有 store 替换为会在 get 时抛 RuntimeException 的 Map，
        // 触发 loadHistory 内部 catch 分支，验证降级为单轮（空列表）
        Field storeField = SessionContextServiceMock.class.getDeclaredField("store");
        storeField.setAccessible(true);
        storeField.set(svc, new ConcurrentHashMap<String, Object>() {
            @Override
            public Object get(Object key) {
                throw new RuntimeException("REDIS_DOWN");
            }
        });

        // 即使底层炸了，loadHistory 必须返回空列表且绝不抛出（降级纯粹性）
        List<ChatTurn> result = svc.loadHistory("M-1001", "s1");
        assertEquals(0, result.size());
    }

    @Test
    void 降级纯粹性_过期会话返回空列表() throws Exception {
        SessionContextServiceMock svc = newMock();
        svc.appendTurn("M-1001", "s1", turn("问题", "答复"));
        // 将 TTL 设为极小值，使刚写入的会话立即过期
        Field ttlField = SessionContextServiceMock.class.getDeclaredField("ttlMs");
        ttlField.setAccessible(true);
        ttlField.set(svc, 1L);
        Thread.sleep(5); // 超过 1ms TTL
        assertEquals(0, svc.loadHistory("M-1001", "s1").size());
    }

    // ===== 红线 6：并发安全（同 session 多线程 appendTurn 不抛 ConcurrentModificationException）=====
    @Test
    void 并发安全_同会话多线程appendTurn不抛异常() throws InterruptedException {
        SessionContextService svc = newMock();
        int threads = 16;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        svc.appendTurn("M-1001", "s1",
                                turn("t" + tid + "-q" + i, "t" + tid + "-a" + i));
                        if (i % 5 == 0) {
                            svc.loadHistory("M-1001", "s1"); // 读写并发
                        }
                    }
                } catch (Throwable e) {
                    thrown.set(e); // 捕获任何异常（含 CME）
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(finished, "并发测试应在 10s 内完成");
        // 核心断言：不得出现任何异常（尤其 ConcurrentModificationException）
        assertEquals(null, thrown.get(),
                "同会话并发 appendTurn/loadHistory 不应抛异常，实际=" + thrown.get());
        // 最终保留 <= 滑动窗口上限（裁剪生效）
        assertTrue(svc.loadHistory("M-1001", "s1").size() <= 8);
    }
}
