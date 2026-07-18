package com.quant.stock.market.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorDailyDO {
    private Long id;
    private String symbol;
    private LocalDate tradeDate;
    private BigDecimal ma5;
    private BigDecimal ma20;
    private BigDecimal ma60;
    private BigDecimal rsi14;
    private BigDecimal atr14;
    private BigDecimal adx;
    private BigDecimal volumeMa20;
    private Integer ma60Up;
    private Integer isVolumeBreak;
}
