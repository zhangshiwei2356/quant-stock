package com.quant.stock.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingleStockBackResult {

    private String stockCode;
    private BigDecimal totalRate;
    private BigDecimal maxDrawDown;
    private Integer totalTradeNum;
    private BigDecimal winRate;
    private BigDecimal finalAsset;
}
