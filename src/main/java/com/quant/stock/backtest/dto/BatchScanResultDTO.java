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
public class BatchScanResultDTO {

    private String stockCode;
    private BigDecimal lastClose;
    private BigDecimal totalRate;
    private BigDecimal maxDrawDown;
    private BigDecimal winRate;
    private Integer totalTradeNum;
    private Boolean canBuyNow;
    private String signalDesc;
    private BigDecimal ma5;
    private BigDecimal ma20;
    private BigDecimal rsi14;
    private BigDecimal atr14;
}
