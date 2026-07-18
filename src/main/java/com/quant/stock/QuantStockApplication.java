package com.quant.stock;

import com.quant.stock.config.QuantProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类。
 * <ul>
 *   <li>默认连接本地 MySQL（localhost:3306/quant_stock），读取 market_daily / market_minute</li>
 *   <li>空库时自动从 classpath:data/kline 导入 DAY+MIN_5 模拟数据</li>
 *   <li>仍默认不连 Redis</li>
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
