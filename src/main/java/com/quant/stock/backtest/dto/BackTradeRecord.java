package com.quant.stock.backtest.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 回测单笔成交记录（买卖方向、价量与费用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackTradeRecord {

    /** 标的代码 */
    private String stockCode;
    /** BUY / SELL */
    private String side;

    /** 成交时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime tradeTime;

    /** 成交价 */
    private BigDecimal price;
    /** 成交股数（整百） */
    private Integer volume;
    /** 本笔费用（佣金+印花税等） */
    private BigDecimal fee;
    /** 成交额（价×量，不含费用） */
    private BigDecimal amount;
}
