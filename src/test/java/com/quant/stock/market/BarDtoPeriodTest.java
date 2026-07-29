package com.quant.stock.market;

import com.quant.stock.market.dto.BarDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BarDtoPeriodTest {
    @Test
    void barEnd_usesPeriodMinutes_defaultFive() {
        BarDTO b = BarDTO.builder()
                .code("600036")
                .barBegin(LocalDateTime.of(2026, 7, 28, 9, 30))
                .open(BigDecimal.ONE).high(BigDecimal.ONE).low(BigDecimal.ONE).close(BigDecimal.ONE)
                .volume(BigDecimal.TEN)
                .build();
        assertEquals(LocalDateTime.of(2026, 7, 28, 9, 35), b.getBarEnd());
    }

    @Test
    void barEnd_oneMinute() {
        BarDTO b = BarDTO.builder()
                .code("600036")
                .barBegin(LocalDateTime.of(2026, 7, 28, 9, 30))
                .periodMinutes(1)
                .open(BigDecimal.ONE).high(BigDecimal.ONE).low(BigDecimal.ONE).close(BigDecimal.ONE)
                .volume(BigDecimal.TEN)
                .build();
        assertEquals(LocalDateTime.of(2026, 7, 28, 9, 31), b.getBarEnd());
    }
}
