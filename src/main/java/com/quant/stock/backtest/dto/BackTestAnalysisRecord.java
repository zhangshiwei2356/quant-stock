package com.quant.stock.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 单次回测的分析记录（落盘 JSON，与成交历史分开）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackTestAnalysisRecord {

    private String id;
    private String savedAt;
    /** SINGLE / PORTFOLIO */
    private String kind;
    private String stockCode;
    private List<String> stockCodeList;
    private String period;
    private String backStart;
    private String backEnd;
    private BigDecimal initCapital;
    private BigDecimal finalAsset;
    private Integer totalTradeNum;
    private Integer eventCount;
    /** 摘要：信号数、成交数等 */
    private String summary;
    @Builder.Default
    private List<AnalysisEvent> events = new ArrayList<AnalysisEvent>();
}
