package com.quant.stock.market.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 5 分钟行情持久化实体，对应表 {@code market_minute}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketMinuteDO {
    private Long id;
    /** 股票代码 */
    private String symbol;
    /** bar 起始时刻 */
    private LocalDateTime tradeTime;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private Long volume;
    private BigDecimal amount;

    /** 转为分钟/5 分钟 {@link BarDTO} */
    public BarDTO toBarDTO() {
        return BarDTO.builder()
                .code(symbol)
                .barBegin(tradeTime)
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .volume(volume == null ? BigDecimal.ZERO : BigDecimal.valueOf(volume))
                .build();
    }

    /** 由 {@link BarDTO} 构造分钟行 */
    public static MarketMinuteDO fromBarDTO(BarDTO bar) {
        if (bar == null) {
            return null;
        }
        Long vol = null;
        if (bar.getVolume() != null) {
            vol = bar.getVolume().longValue();
        }
        return MarketMinuteDO.builder()
                .symbol(bar.getCode())
                .tradeTime(bar.getBarBegin())
                .open(bar.getOpen())
                .high(bar.getHigh())
                .low(bar.getLow())
                .close(bar.getClose())
                .volume(vol)
                .amount(null)
                .build();
    }
}
