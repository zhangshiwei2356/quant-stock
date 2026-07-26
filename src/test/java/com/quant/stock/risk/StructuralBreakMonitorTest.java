package com.quant.stock.risk;

import com.quant.stock.market.dto.BarDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuralBreakMonitorTest {

    @Test
    void scoresHigherAfterRegimeShift() {
        // 前 40 日横盘 + 后 10 日连跌；window=10 → 旧窗横盘、新窗暴跌
        List<BarDTO> bars = new ArrayList<BarDTO>();
        LocalDateTime t = LocalDateTime.of(2024, 1, 2, 15, 0);
        BigDecimal px = new BigDecimal("100");
        for (int i = 0; i < 50; i++) {
            BigDecimal next = i < 40 ? px : px.multiply(new BigDecimal("0.97")).setScale(4, RoundingMode.HALF_UP);
            bars.add(BarDTO.builder()
                    .barBegin(t.plusDays(i))
                    .open(px)
                    .high(next.max(px))
                    .low(next.min(px))
                    .close(next)
                    .volume(new BigDecimal("1000000"))
                    .build());
            px = next;
        }
        int idx = bars.size() - 1;
        BigDecimal score = StructuralBreakMonitor.scoreAt(bars, idx, 10);
        assertNotNull(score);
        assertTrue(score.compareTo(new BigDecimal("1.5")) > 0, "score=" + score);
        assertTrue(StructuralBreakMonitor.crossesThreshold(bars, idx, 10, new BigDecimal("1.5")));
    }
}
