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
public class BtBacktestRecordDO {
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
    private BigDecimal totalRate;
    private BigDecimal maxDrawdown;
    private Integer totalTradeNum;
    private BigDecimal winRate;
    private String tradeStatsJson;
    private String tradesJson;
    private String stockResultsJson;
}
