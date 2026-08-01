package com.quant.stock.admin.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 策略稀疏参数包行，对应表 {@code strategy_param}。 */
@Data
public class StrategyParamDO {
    private String strategyId;
    private String paramsJson;
    private Integer version;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
