package com.quant.stock.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置。
 * <p>
 * 职责：提供全市场/批量扫描等并行任务使用的固定大小线程池 Bean。
 * </p>
 * <p>
 * 关键约束：核心与最大线程数均等于 {@code quant.batch-pool-size}（默认 10）；
 * 队列满时使用 {@link ThreadPoolExecutor.CallerRunsPolicy}，在调用线程同步执行以防静默丢任务。
 * </p>
 */
@Configuration
public class ThreadPoolConfig {

    @Value("${quant.batch-pool-size:10}")
    private int batchPoolSize;

    /**
     * 批量扫描专用执行器，Bean 名 {@code batchScanExecutor}，供 {@code @Async} 或显式注入使用。
     * <p>
     * 队列容量须覆盖全市场规模（约 5000+）：若队列过小，{@link ThreadPoolExecutor.CallerRunsPolicy}
     * 会把大量任务挤到提交线程串行执行，入池扫描会「假卡死」数十分钟。
     * </p>
     *
     * @return 已初始化的 {@link ThreadPoolTaskExecutor} 包装为 {@link Executor}
     */
    @Bean(name = "batchScanExecutor")
    public Executor batchScanExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(batchPoolSize);
        executor.setMaxPoolSize(batchPoolSize);
        executor.setQueueCapacity(10000);
        executor.setThreadNamePrefix("batch-scan-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
