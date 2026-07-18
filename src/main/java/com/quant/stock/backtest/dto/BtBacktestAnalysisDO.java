package com.quant.stock.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BtBacktestAnalysisDO {
    private Long id;
    private String recordId;
    private String kind;
    private LocalDateTime savedAt;
    private String stockCode;
    private String stockCodesJson;
    private String period;
    private String backStart;
    private String backEnd;
    private BigDecimal initCapital;
    private BigDecimal finalAsset;
    private Integer totalTradeNum;
    private Integer eventCount;
    private String summary;
    private String eventsJson;
}
