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
 * 分钟/多周期 K 线实体。物理真相源为 5 分钟；{@link #getBarEnd()} 默认 +5 分钟。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BarDTO {

    private String code;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime barBegin;

    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal volume;

    /** 5 分钟 K 结束时间（与 market_minute 物理粒度一致） */
    public LocalDateTime getBarEnd() {
        if (barBegin == null) {
            return null;
        }
        return barBegin.plusMinutes(5);
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
                Duration.ofMinutes(5),
                endTime,
                open,
                high,
                low,
                close,
                volume
        );
    }
}
