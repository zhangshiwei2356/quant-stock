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

    private String stockCode;
    private LocalDateTime barTime;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private Long volume;
    private BigDecimal amount;

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
