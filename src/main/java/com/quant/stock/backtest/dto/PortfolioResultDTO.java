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
                .build();
    }
}
