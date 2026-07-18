package com.quant.stock.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackTestResult {

    private String stockCode;
    private BigDecimal initCapital;
    private BigDecimal finalAsset;
    private BigDecimal totalRate;
    private BigDecimal maxDrawDown;
    private Integer totalTradeNum;
    private BigDecimal winRate;
    private List<BackTradeRecord> trades;
    private List<String> equityTimes;
    private List<BigDecimal> equityCurve;
    private List<MarkPoint> buyMarks;
    private List<MarkPoint> sellMarks;
    /** 本次回测决策分析事件（为何买卖、看了哪些数据、买多少） */
    private List<AnalysisEvent> analysisEvents;
    private String analysisSummary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MarkPoint {
        private String time;
        private BigDecimal price;
    }

    public static BackTestResult empty(String code, BigDecimal init) {
        return BackTestResult.builder()
                .stockCode(code)
                .initCapital(init)
                .finalAsset(init)
                .totalRate(BigDecimal.ZERO)
                .maxDrawDown(BigDecimal.ZERO)
                .totalTradeNum(0)
                .winRate(BigDecimal.ZERO)
                .trades(new ArrayList<BackTradeRecord>())
                .equityTimes(new ArrayList<String>())
                .equityCurve(new ArrayList<BigDecimal>())
                .buyMarks(new ArrayList<MarkPoint>())
                .sellMarks(new ArrayList<MarkPoint>())
                .analysisEvents(new ArrayList<AnalysisEvent>())
                .analysisSummary("")
                .build();
    }
}
