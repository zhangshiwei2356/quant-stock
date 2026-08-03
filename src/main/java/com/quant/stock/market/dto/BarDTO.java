package com.quant.stock.market.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 分钟/多周期 K 线实体。物理来源可为 1 分钟或 5 分钟；{@link #getBarEnd()} = begin + periodMinutes（默认 5）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BarDTO {

    /** 股票代码 */
    private String code;

    /** K 线起始时刻（bar 左端） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime barBegin;

    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    /** 成交量（股/手口径与数据源一致） */
    private BigDecimal volume;
    /** 成交额（元）；可选，MDS 等源写入 {@code market_1min.amount} */
    private BigDecimal amount;

    /** K 线周期（分钟）；null 时按 5 分钟（兼容既有 5 分钟调用方） */
    private Integer periodMinutes;

    public LocalDateTime getBarEnd() {
        if (barBegin == null) {
            return null;
        }
        int mins = periodMinutes == null ? 5 : periodMinutes.intValue();
        return barBegin.plusMinutes(mins);
    }

    /** 当前系统时间是否已超过K线结束时间（完整闭合） */
    public boolean isClosedBar() {
        LocalDateTime end = getBarEnd();
        return end != null && !LocalDateTime.now().isBefore(end);
    }

    /** 转换为 TA4J Bar（endTime 为 bar 结束时刻） */
    public Bar toTa4jBar() {
        ZonedDateTime endTime = ZonedDateTime.of(getBarEnd(), ZoneId.systemDefault());
        return new BaseBar(
                Duration.ofMinutes(periodMinutes == null ? 5 : periodMinutes),
                endTime,
                open,
                high,
                low,
                close,
                volume
        );
    }
}
