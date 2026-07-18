package com.quant.stock.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ThreadPoolConfig {

    @Value("${quant.batch-pool-size:10}")
    private int batchPoolSize;

    @Bean(name = "batchScanExecutor")
    public Executor batchScanExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(batchPoolSize);
        executor.setMaxPoolSize(batchPoolSize);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("batch-scan-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
