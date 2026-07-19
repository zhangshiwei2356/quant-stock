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
public class TradePoolDO {
    private Long id;
    private String symbol;
    private String name;
    /** 1 活跃 0 停用 */
    private Integer status;
    private BigDecimal score;
    private String reason;
    /** BATCH_SCAN / MANUAL */
    private String source;
    /** 关联推荐报告 */
    private Long reportId;
    private LocalDateTime enteredAt;
    private LocalDateTime updatedAt;
}
