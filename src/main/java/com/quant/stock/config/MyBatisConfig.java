package com.quant.stock.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * 仅当 quant.db-enabled=true 时启用 MyBatis Mapper 扫描
 */
@Configuration
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
@MapperScan("com.quant.stock.mapper")
public class MyBatisConfig {
}
