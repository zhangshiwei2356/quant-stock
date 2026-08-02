package com.quant.stock.session;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionParticipationTest {

    @Test
    void bypassLeavesVolumeUntouched() {
        QuantProperties p = new QuantProperties();
        p.setMaxParticipationAdv(new BigDecimal("0.01"));
        int v = SessionParticipation.capVolume(500, bars(1000), 0, p, new BigDecimal("100000"), true);
        assertEquals(500, v);
    }

    @Test
    void povCapsAgainstBarVolume() {
        QuantProperties p = new QuantProperties();
        p.setMaxParticipationAdv(new BigDecimal("1")); // ADV 不挡
        p.setCapacityAumBase(new BigDecimal("1000000"));
        p.setPovMaxBarVolumePct(new BigDecimal("0.10")); // 当根 10000 → 最多 1000 股
        int v = SessionParticipation.capVolume(5000, bars(10000), 0, p, new BigDecimal("100000"), false);
        assertTrue(v <= 1000);
        assertEquals(0, v % 100);
    }

    private static List<BarDTO> bars(long vol) {
        List<BarDTO> list = new ArrayList<BarDTO>();
        list.add(BarDTO.builder()
                .code("600036")
                .barBegin(LocalDateTime.of(2026, 3, 2, 10, 0))
                .open(new BigDecimal("10")).high(new BigDecimal("10"))
                .low(new BigDecimal("10")).close(new BigDecimal("10"))
                .volume(BigDecimal.valueOf(vol))
                .build());
        return list;
    }
}
