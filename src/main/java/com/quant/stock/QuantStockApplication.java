package com.quant.stock;

import com.quant.stock.config.QuantProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类。
 * <ul>
 *   <li>默认：不连 MySQL/Redis（application.yml 排除自动配置），内存 mock 行情演示</li>
 *   <li>连库：--spring.profiles.active=db ，并执行 mapper/schema.sql</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(QuantProperties.class)
public class QuantStockApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuantStockApplication.class, args);
    }
}
