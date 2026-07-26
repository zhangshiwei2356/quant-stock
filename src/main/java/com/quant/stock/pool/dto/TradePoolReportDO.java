package com.quant.stock.pool.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 目标池入选分析报告行，对应表 {@code trade_pool_report}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradePoolReportDO {
    private Long id;
    /** 股票代码 */
    private String symbol;
    private String name;
    /** 综合打分 */
    private BigDecimal score;
    /** 入选依据摘要 */
    private String reason;
    /** 可读摘要（页面展示） */
    private String summary;
    /** 完整分析 JSON */
    private String analysisJson;
    /** 同一次扫描批次号 */
    private String batchId;
    private LocalDateTime createdAt;
}
