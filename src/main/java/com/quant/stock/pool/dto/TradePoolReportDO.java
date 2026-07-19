package com.quant.stock.pool.dto;

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
public class TradePoolReportDO {
    private Long id;
    private String symbol;
    private String name;
    private BigDecimal score;
    private String reason;
    private String summary;
    private String analysisJson;
    private String batchId;
    private LocalDateTime createdAt;
}
