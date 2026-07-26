package com.quant.stock.risk;

import com.quant.stock.market.dto.BarDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StressScenarioServiceTest {

    @Test
    void detectsAdvCliffWhenRecentVolumeCollapsed() {
        List<BarDTO> bars = new ArrayList<BarDTO>();
        LocalDateTime t = LocalDateTime.of(2024, 1, 2, 15, 0);
        for (int i = 0; i < 80; i++) {
            BigDecimal vol = i < 60 ? new BigDecimal("1000000") : new BigDecimal("100000");
            bars.add(BarDTO.builder()
                    .barBegin(t.plusDays(i))
                    .open(new BigDecimal("10"))
                    .high(new BigDecimal("10.2"))
                    .low(new BigDecimal("9.8"))
                    .close(new BigDecimal("10"))
                    .volume(vol)
                    .build());
        }
        assertTrue(StressScenarioService.isAdvCliff(bars, 79, new BigDecimal("0.40")));
        assertFalse(StressScenarioService.isAdvCliff(bars, 50, new BigDecimal("0.40")));
    }
}
