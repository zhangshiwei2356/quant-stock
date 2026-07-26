package com.quant.stock.market.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 日线行情持久化实体，对应表 {@code market_daily}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketDailyDO {
    private Long id;
    /** 股票代码 */
    private String symbol;
    /** 交易日期 */
    private LocalDate tradeDate;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private Long volume;
    private BigDecimal amount;
    /** 换手率 */
    private BigDecimal turnoverRate;
    /** 涨停价（前收推算，首日可为空） */
    private BigDecimal limitUp;
    /** 跌停价 */
    private BigDecimal limitDown;

    /** 转为日 K {@link BarDTO}（bar 时间取当日 9:30） */
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

    /** 由 {@link BarDTO} 构造日线行（涨跌停等扩展字段默认 null） */
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
