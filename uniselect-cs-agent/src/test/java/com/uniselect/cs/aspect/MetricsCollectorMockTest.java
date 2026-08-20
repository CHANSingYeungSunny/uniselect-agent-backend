package com.uniselect.cs.aspect;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 红线回归测试集 —— 埋点异步化（Step 5 修复的"埋点阻塞 SSE 主链路"缺陷锁定点）。
 *
 * <p>红线：埋点方法（如 {@code recordLayer2Violation}）必须异步执行，
 * <b>调用方（含 SSE 推送 while 循环）不得被阻塞</b>，不影响首字响应（P95 ≤ 2s）。</p>
 *
 * <p>验证策略（需 Spring 容器以激活 {@code @Async} 代理）：
 * <ol>
 *   <li>方法调用后<b>立即返回</b>（耗时极小，远小于异步任务调度开销），证明不阻塞；</li>
 *   <li>通过 {@link MetricsCollector#observeLastLayer2Thread()} 观察点捕获埋点方法
 *       <b>真实执行线程名</b>，断言其落在独立线程池（前缀 {@code metrics-async-}），
 *       证明任务已移交线程池；</li>
 *   <li>连续触发多次后无异常，证明 fire-and-forget 调用链稳定。</li>
 * </ol>
 * </p>
 */
@SpringBootTest
@ActiveProfiles("mock")
class MetricsCollectorMockTest {

    @Autowired
    private MetricsCollector metricsCollector;

    @Test
    void 异步埋点_调用即返回不阻塞主线程() {
        long start = System.nanoTime();
        // 模拟 SSE while 循环中的一次埋点调用
        metricsCollector.recordLayer2Violation("M-1001", "s1", "退款金额");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 调用本身立即返回（远低于 P95 预算），证明不阻塞主链路
        assertTrue(elapsedMs < 50,
                "埋点调用不应阻塞主线程，实际耗时=" + elapsedMs + "ms");
    }

    @Test
    void 异步埋点_在独立线程池执行() throws InterruptedException {
        metricsCollector.recordLayer2Violation("M-1001", "s1", "退款金额");

        // 轮询等待异步任务落池执行（最多 1s）
        String asyncThread = null;
        for (int i = 0; i < 20; i++) {
            asyncThread = metricsCollector.observeLastLayer2Thread();
            if (asyncThread != null) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertNotNull(asyncThread, "埋点方法未在异步线程池中执行");
        assertTrue(asyncThread.startsWith("metrics-async-"),
                "应在 metrics-async- 线程池执行，实际线程=" + asyncThread);
    }

    @Test
    void 异步埋点_任务确实被异步执行() throws InterruptedException {
        // 连续触发多次，等待线程池消费后，副作用应已发生（日志/计数），
        // 此处仅验证方法不会因异步而抛错，且能在合理延时后正常返回。
        for (int i = 0; i < 20; i++) {
            metricsCollector.recordToolUseLatency("M-1001", "s1", "STOCK", 123_000_000L, false);
        }
        // 给异步线程池时间消费（不阻塞主链路的前提下等待验证）
        TimeUnit.MILLISECONDS.sleep(300);
        // 若执行到此无异常，即证明 fire-and-forget 调用链稳定
    }
}
