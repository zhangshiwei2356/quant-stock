package com.quant.stock;

import com.quant.stock.config.QuantProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot 应用入口。
 * <p>
 * 职责：装配量化工作台（行情、回测、目标池、模拟账户、运维调度等）并启用定时任务与 {@link QuantProperties} 绑定。
 * </p>
 * <p>
 * 关键约束：默认 {@code quant.db-enabled=false} 时走 JSON/mock 行情；启用库后依赖 MySQL 与可选 Redis；
 * 调度总闸与 cron 以库表 {@code sys_schedule_job} 为准（见 {@link QuantProperties.Schedule}）。
 * </p>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(QuantProperties.class)
public class QuantStockApplication {

    /**
     * 启动 Spring 容器；命令行参数会传入 Spring Boot 标准配置（如 {@code --server.port}）。
     *
     * @param args 启动参数，可为空
     */
    public static void main(String[] args) {
        SpringApplication.run(QuantStockApplication.class, args);
    }
}
