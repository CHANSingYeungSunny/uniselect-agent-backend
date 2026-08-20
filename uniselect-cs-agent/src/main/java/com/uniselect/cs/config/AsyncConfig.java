package com.uniselect.cs.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步配置（Step 5：埋点非阻塞）。
 *
 * <p>埋点动作（转人工触发、拦截命中、耗时等）通过独立线程池异步执行，
 * <b>绝不阻塞 SSE 流式主链路</b>，不影响首字响应（P95 ≤ 2s 红线）。
 * 独立线程池避免与业务/LLM 流式线程争抢。</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("metricsExecutor")
    public TaskExecutor metricsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("metrics-async-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
