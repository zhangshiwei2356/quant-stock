package com.quant.stock.admin;

import com.quant.stock.market.dto.BarDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataReconcileGateServiceTest {

    @Test
    void ohlcOk_rejectsInvertedHighLow() {
        BarDTO bad = BarDTO.builder()
                .barBegin(LocalDateTime.of(2026, 7, 28, 9, 30))
                .open(new BigDecimal("10"))
                .high(new BigDecimal("9"))
                .low(new BigDecimal("8"))
                .close(new BigDecimal("9.5"))
                .build();
        assertFalse(DataReconcileGateService.ohlcOk(bad));
    }

    @Test
    void ohlcOk_acceptsNormalBar() {
        BarDTO ok = BarDTO.builder()
                .barBegin(LocalDateTime.of(2026, 7, 28, 9, 30))
                .open(new BigDecimal("10"))
                .high(new BigDecimal("11"))
                .low(new BigDecimal("9"))
                .close(new BigDecimal("10.5"))
                .build();
        assertTrue(DataReconcileGateService.ohlcOk(ok));
    }
}
