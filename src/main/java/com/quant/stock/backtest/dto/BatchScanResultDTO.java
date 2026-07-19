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
    /** 历史回测收益率（辅助参考，不再作为入池主排序） */
    private BigDecimal totalRate;
    private BigDecimal maxDrawDown;
    private BigDecimal winRate;
    private Integer totalTradeNum;
    private Boolean canBuyNow;
    private String signalDesc;
    private BigDecimal ma5;
    private BigDecimal ma10;
    private BigDecimal ma20;
    private BigDecimal ma60;
    private BigDecimal rsi14;
    private BigDecimal atr14;
    private BigDecimal adx14;
    /** 近 5 日涨跌幅 */
    private BigDecimal mom5;
    /** 近 20 日涨跌幅 */
    private BigDecimal mom20;
    /** 近 20 日均成交额近似（均量 × 收盘价） */
    private BigDecimal avgAmount20;
    /** MA60 是否较 5 日前上行 */
    private Boolean ma60SlopeUp;
    /** 多因子综合分 0~100（扫池主排序） */
    private BigDecimal poolScore;
    /** 入选标签摘要 */
    private String scoreTag;
    /** 近 20 日动量在本批扫描中的百分位 0~1（越高越强） */
    private BigDecimal mom20Percentile;
}
