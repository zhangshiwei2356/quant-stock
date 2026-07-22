package com.quant.stock.market.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketDailyDO {
    private Long id;
    private String symbol;
    private LocalDate tradeDate;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private Long volume;
    private BigDecimal amount;
    private BigDecimal turnoverRate;
    private BigDecimal limitUp;
    private BigDecimal limitDown;

    public BarDTO toBarDTO() {
        return BarDTO.builder()
                .code(symbol)
                .barBegin(LocalDateTime.of(tradeDate, LocalTime.of(9, 30)))
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .volume(volume == null ? BigDecimal.ZERO : BigDecimal.valueOf(volume))
                .build();
    }

    public static MarketDailyDO fromBarDTO(BarDTO bar) {
        if (bar == null || bar.getBarBegin() == null) {
            return null;
        }
        Long vol = null;
        if (bar.getVolume() != null) {
            vol = bar.getVolume().longValue();
        }
        return MarketDailyDO.builder()
                .symbol(bar.getCode())
                .tradeDate(bar.getBarBegin().toLocalDate())
                .open(bar.getOpen())
                .high(bar.getHigh())
                .low(bar.getLow())
                .close(bar.getClose())
                .volume(vol)
                .amount(null)
                .build();
    }
}
