package com.quant.stock.market.dto;

import com.quant.stock.market.MarketDataSources;
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
 * 价额一律为「元」；首期 {@code adjFlag} 统一 {@code NONE}（与 TDX 裸价一致）。
 */
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
    /** NONE / QFQ */
    private String adjFlag;
    /** TDX / EM / BAO / MOCK … */
    private String dataSource;
    private LocalDateTime ingestedAt;

    /** A 股日 K 在图上的左端：开盘 09:30；闭合按至 15:00（330 分钟） */
    private static final LocalTime DAY_BAR_BEGIN = LocalTime.of(9, 30);
    private static final int DAY_PERIOD_MINUTES = 330;

    /** 转为日线 {@link BarDTO} */
    public BarDTO toBarDTO() {
        LocalDateTime begin = tradeDate == null ? null : LocalDateTime.of(tradeDate, DAY_BAR_BEGIN);
        return BarDTO.builder()
                .code(symbol)
                .barBegin(begin)
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .volume(volume == null ? BigDecimal.ZERO : BigDecimal.valueOf(volume))
                .amount(amount)
                .periodMinutes(DAY_PERIOD_MINUTES)
                .build();
    }

    /** 由日线 {@link BarDTO} 构造行（默认 NONE + TDX） */
    public static MarketDailyDO fromBarDTO(BarDTO bar) {
        return fromBarDTO(bar, "NONE", MarketDataSources.TDX);
    }

    /** 由日线 {@link BarDTO} 构造行并指定复权与来源 */
    public static MarketDailyDO fromBarDTO(BarDTO bar, String adjFlag, String dataSource) {
        if (bar == null || bar.getBarBegin() == null) {
            return null;
        }
        Long vol = null;
        if (bar.getVolume() != null) {
            vol = bar.getVolume().longValue();
        }
        String adj = adjFlag == null || adjFlag.trim().isEmpty() ? "NONE" : adjFlag.trim().toUpperCase();
        return MarketDailyDO.builder()
                .symbol(bar.getCode())
                .tradeDate(bar.getBarBegin().toLocalDate())
                .open(bar.getOpen())
                .high(bar.getHigh())
                .low(bar.getLow())
                .close(bar.getClose())
                .volume(vol)
                .amount(bar.getAmount())
                .adjFlag(adj)
                .dataSource(MarketDataSources.normalize(dataSource))
                .build();
    }
}
