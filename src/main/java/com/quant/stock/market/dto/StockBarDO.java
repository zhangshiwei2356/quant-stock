package com.quant.stock.market.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * K线持久化实体，对应 stock_bar_* 统一字段
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockBarDO {

    /** 股票代码 */
    private String stockCode;
    /** K 线时间（bar 起始） */
    private LocalDateTime barTime;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    /** 成交量 */
    private Long volume;
    /** 成交额（可为空） */
    private BigDecimal amount;

    /** 转为 API/引擎使用的 {@link BarDTO} */
    public BarDTO toBarDTO() {
        return BarDTO.builder()
                .code(stockCode)
                .barBegin(barTime)
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .volume(volume == null ? BigDecimal.ZERO : BigDecimal.valueOf(volume))
                .build();
    }

    /** 由 {@link BarDTO} 构造持久化行（成交额默认 null） */
    public static StockBarDO fromBarDTO(BarDTO bar) {
        if (bar == null) {
            return null;
        }
        return StockBarDO.builder()
                .stockCode(bar.getCode())
                .barTime(bar.getBarBegin())
                .open(bar.getOpen())
                .high(bar.getHigh())
                .low(bar.getLow())
                .close(bar.getClose())
                .volume(bar.getVolume() == null ? 0L : bar.getVolume().longValue())
                .amount(null)
                .build();
    }
}
