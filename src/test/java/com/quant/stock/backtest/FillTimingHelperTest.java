package com.quant.stock.backtest;

import com.quant.stock.market.dto.BarDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FillTimingHelperTest {

    @Test
    void daySeries_notMinute() {
        List<BarDTO> bars = new ArrayList<BarDTO>();
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 15, 0);
        for (int i = 0; i < 5; i++) {
            bars.add(bar(t.plusDays(i)));
        }
        assertFalse(FillTimingHelper.isMinuteSeries(bars, 2));
        assertTrue(FillTimingHelper.canFillPendingOnBar(bars, 2));
    }

    @Test
    void minuteSeries_fillAfter0945() {
        List<BarDTO> bars = new ArrayList<BarDTO>();
        LocalDateTime day = LocalDateTime.of(2026, 1, 2, 9, 30);
        for (int i = 0; i < 20; i++) {
            bars.add(bar(day.plusMinutes(i)));
        }
        assertTrue(FillTimingHelper.isMinuteSeries(bars, 10));
        assertFalse(FillTimingHelper.canFillPendingOnBar(bars, 0)); // 09:30
        assertTrue(FillTimingHelper.canFillPendingOnBar(bars, 15)); // 09:45
        assertTrue(FillTimingHelper.isOpenQuietMinute(day));
        assertFalse(FillTimingHelper.isOpenQuietMinute(day.plusMinutes(15)));
    }

    private static BarDTO bar(LocalDateTime t) {
        return BarDTO.builder()
                .code("600036")
                .barBegin(t)
                .open(BigDecimal.TEN)
                .high(BigDecimal.TEN)
                .low(BigDecimal.TEN)
                .close(BigDecimal.TEN)
                .volume(BigDecimal.valueOf(1000))
                .build();
    }
}
