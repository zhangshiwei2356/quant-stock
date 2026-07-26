package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitPriceProtectStatusTest {

    @Test
    void declaresFiveLevelUnavailable() {
        QuantProperties p = new QuantProperties();
        Map<String, Object> m = LimitPriceProtect.status(p);
        assertEquals(Boolean.TRUE, m.get("enabled"));
        assertEquals(Boolean.TRUE, m.get("bookClamp"));
        assertEquals("UNAVAILABLE", m.get("fiveLevelBook"));
        assertEquals("UNAVAILABLE", m.get("l2Depth"));
        assertTrue(String.valueOf(m.get("hint")).contains("UNAVAILABLE"));
    }
}
