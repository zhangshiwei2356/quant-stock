package com.quant.stock.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 扫描配置。
 * <p>
 * 职责：在启用数据库模式时注册 {@code com.quant.stock.mapper} 包下的 Mapper 接口。
 * </p>
 * <p>
 * 关键约束：仅当配置 {@code quant.db-enabled=true} 时本配置类生效；否则不创建 Mapper Bean，业务走 JSON/mock 路径。
 * </p>
 */
@Configuration
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
@MapperScan("com.quant.stock.mapper")
public class MyBatisConfig {
}
