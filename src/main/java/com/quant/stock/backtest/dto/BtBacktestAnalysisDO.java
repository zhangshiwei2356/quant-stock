package com.quant.stock.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 表 {@code bt_backtest_analysis} 行映射：回测决策分析事件持久化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BtBacktestAnalysisDO {
    private Long id;
    /** 与回测历史 recordId 对齐 */
    private String recordId;
    /** SINGLE / PORTFOLIO */
    private String kind;
    private LocalDateTime savedAt;
    private String stockCode;
    /** 组合成分股 JSON */
    private String stockCodesJson;
    private String period;
    private String backStart;
    private String backEnd;
    private BigDecimal initCapital;
    private BigDecimal finalAsset;
    private Integer totalTradeNum;
    /** 分析事件条数 */
    private Integer eventCount;
    /** 文本摘要 */
    private String summary;
    /** {@link AnalysisEvent} 列表 JSON */
    private String eventsJson;
}
