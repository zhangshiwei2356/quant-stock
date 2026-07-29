package com.quant.stock.market;

import com.quant.stock.market.dto.BarDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OneMinAggregatePathTest {

    @Test
    void aggregate_fiveOneMin_toOneFiveMin() {
        List<BarDTO> ones = new ArrayList<BarDTO>();
        LocalDateTime t = LocalDateTime.of(2026, 7, 28, 9, 30);
        for (int i = 0; i < 5; i++) {
            ones.add(BarDTO.builder().code("600036").barBegin(t.plusMinutes(i)).periodMinutes(1)
                    .open(new BigDecimal("10")).high(new BigDecimal("11"))
                    .low(new BigDecimal("9")).close(new BigDecimal("10.5"))
                    .volume(BigDecimal.valueOf(100)).build());
        }

        List<BarDTO> m5 = BarAggregateUtil.aggregate(ones, BarAggregateUtil.Period.M5);

        assertEquals(1, m5.size());
        assertEquals(t, m5.get(0).getBarBegin());
    }
}
