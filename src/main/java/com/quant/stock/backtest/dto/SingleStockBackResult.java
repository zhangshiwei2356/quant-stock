package com.quant.stock.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 组合回测中单只成分股的绩效摘要（不含成交流水）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingleStockBackResult {

    /** 标的代码 */
    private String stockCode;
    /** 该成分股贡献的总收益率 */
    private BigDecimal totalRate;
    /** 该成分股路径上的最大回撤 */
    private BigDecimal maxDrawDown;
    /** 成交笔数 */
    private Integer totalTradeNum;
    /** 胜率 */
    private BigDecimal winRate;
    /** 该成分股期末市值+分摊现金近似 */
    private BigDecimal finalAsset;
}
