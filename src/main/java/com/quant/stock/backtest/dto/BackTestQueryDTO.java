package com.quant.stock.backtest.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 组合回测请求参数：区间、资金、标的列表与可选费率覆盖。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackTestQueryDTO {

    /** 回测起始时间（含） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime backStart;

    /** 回测结束时间（含） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime backEnd;

    /** 初始资金；空则引擎默认 */
    private BigDecimal initCapital;
    /** 成分股代码列表 */
    private List<String> stockCodeList;
    /** 佣金率覆盖；空则用配置 */
    private BigDecimal feeRate;
    /** 滑点参数（合法性校验；实际滑点由 TradeCostModel 分级） */
    private BigDecimal slipPoint;
    /** 策略 id（如 maCross）；空则用 quant.active-strategy */
    private String strategyId;
}
