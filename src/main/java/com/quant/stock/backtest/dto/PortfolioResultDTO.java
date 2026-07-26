package com.quant.stock.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组合回测结果：共享资金池下的组合绩效、分股摘要与相关监控快照。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioResultDTO {

    /** 初始资金 */
    private BigDecimal initCapital;
    /** 期末总资产 */
    private BigDecimal finalAsset;
    /** 组合总收益率 */
    private BigDecimal totalRate;
    /** 组合最大回撤 */
    private BigDecimal maxDrawDown;
    /** 成交笔数 */
    private Integer totalTradeNum;
    /** 胜率 */
    private BigDecimal winRate;
    /** 权益曲线时间轴 */
    private List<String> equityTimes;
    /** 权益曲线数值 */
    private List<BigDecimal> equityCurve;
    /** 各成分股绩效摘要 */
    private List<SingleStockBackResult> stockResults;
    /** 组合成交流水（按时间排序） */
    private List<BackTradeRecord> trades;
    /** 决策分析事件（可由成交反推或引擎详细记录） */
    private List<AnalysisEvent> analysisEvents;
    /** 分析摘要文本 */
    private String analysisSummary;
    /** 策略相关配置指纹（P0-93） */
    private String configFingerprint;
    /** 成分股两两收益相关摘要（P0-105） */
    private Map<String, Object> correlation;
    /** ATR 止损/定仓一体契约快照（P0-108，与单股对齐） */
    private Map<String, Object> atrRisk;

    /** 无有效标的或参数无效时的空结果 */
    public static PortfolioResultDTO empty(BigDecimal init) {
        return PortfolioResultDTO.builder()
                .initCapital(init)
                .finalAsset(init)
                .totalRate(BigDecimal.ZERO)
                .maxDrawDown(BigDecimal.ZERO)
                .totalTradeNum(0)
                .winRate(BigDecimal.ZERO)
                .equityTimes(new ArrayList<String>())
                .equityCurve(new ArrayList<BigDecimal>())
                .stockResults(new ArrayList<SingleStockBackResult>())
                .trades(new ArrayList<BackTradeRecord>())
                .analysisEvents(new ArrayList<AnalysisEvent>())
                .analysisSummary("")
                .atrRisk(new LinkedHashMap<String, Object>())
                .build();
    }
}
