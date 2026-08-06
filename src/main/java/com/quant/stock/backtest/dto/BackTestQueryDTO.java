package com.quant.stock.backtest.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
    /**
     * 单次回测临时参数覆盖（白名单键，如 feeRate / atrStopMultiplier）；
     * 叠在策略包之上，不落库、不影响运维全局。
     */
    private Map<String, String> paramOverrides;
    /**
     * 引擎：{@code classic}（默认共享资金池日 K）或 {@code session}（MIN_1 会话共享资金池）。
     * 空则：策略实现 {@code SessionStrategy}（如 {@code overnightGap}）时 session，否则 classic。
     */
    private String engine;
    /** session 引擎：缺依赖是否整单失败；默认 false */
    private Boolean failOnMissingDep;
}
