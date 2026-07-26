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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioResultDTO {

    private BigDecimal initCapital;
    private BigDecimal finalAsset;
    private BigDecimal totalRate;
    private BigDecimal maxDrawDown;
    private Integer totalTradeNum;
    private BigDecimal winRate;
    private List<String> equityTimes;
    private List<BigDecimal> equityCurve;
    private List<SingleStockBackResult> stockResults;
    private List<BackTradeRecord> trades;
    private List<AnalysisEvent> analysisEvents;
    private String analysisSummary;
    /** 策略相关配置指纹（P0-93） */
    private String configFingerprint;
    /** 成分股两两收益相关摘要（P0-105） */
    private Map<String, Object> correlation;
    /** ATR 止损/定仓一体契约快照（P0-108，与单股对齐） */
    private Map<String, Object> atrRisk;

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
