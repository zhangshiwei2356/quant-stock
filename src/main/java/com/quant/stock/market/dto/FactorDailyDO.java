package com.quant.stock.market.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日频技术因子缓存，对应表 {@code factor_daily}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorDailyDO {
    private Long id;
    /** 股票代码 */
    private String symbol;
    /** 交易日期 */
    private LocalDate tradeDate;
    private BigDecimal ma5;
    private BigDecimal ma20;
    private BigDecimal ma60;
    private BigDecimal rsi14;
    private BigDecimal atr14;
    private BigDecimal adx;
    private BigDecimal volumeMa20;
    /** MA60 相对前一日是否上行：1 是 / 0 否 */
    private Integer ma60Up;
    /** 是否放量突破：1 是 / 0 否 */
    private Integer isVolumeBreak;
}
