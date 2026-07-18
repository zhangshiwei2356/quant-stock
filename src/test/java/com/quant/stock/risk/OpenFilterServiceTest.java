package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenFilterServiceTest {

    private QuantProperties props;
    private OpenFilterService filter;

    @BeforeEach
    void setUp() {
        props = new QuantProperties();
        props.setMinAvgVolume20(1L);
        props.setMinMarketCapYi(new BigDecimal("50"));
        props.setMarketCapFilterEnabled(true);
        props.setQuietOpenEnabled(true);
        filter = new OpenFilterService(props);
    }

    @Test
    void limitUpVsPrevTradingDay() {
        List<BarDTO> bars = dayBars(
                "10.00", "10.00",
                "10.95", "10.95"); // +9.5% soft board
        assertTrue(filter.isLimitUpAt(bars, 1));
        assertFalse(filter.isLimitDownAt(bars, 1));
    }

    @Test
    void gemBoardUses20Pct() {
        List<BarDTO> bars = new ArrayList<BarDTO>();
        bars.add(bar("300059", LocalDateTime.of(2026, 1, 2, 15, 0), "10.00", "1000"));
        bars.add(bar("300059", LocalDateTime.of(2026, 1, 3, 15, 0), "11.90", "1000")); // +19%
        assertTrue(filter.isLimitUpAt(bars, 1));
    }

    @Test
    void floatSharesOverrideMarketCap() {
        Map<String, BigDecimal> map = new HashMap<String, BigDecimal>();
        map.put("600036", new BigDecimal("1")); // 价格10 → 市值10亿 < 50
        props.setFloatSharesYi(map);
        assertEquals(0, new BigDecimal("10.00").compareTo(filter.estimateMarketCapYi("600036", new BigDecimal("10"))));
        List<BarDTO> bars = dayBars("10", "10", "10.10", "10.10");
        bars.get(0).setCode("600036");
        bars.get(1).setCode("600036");
        assertFalse(filter.canOpen("600036", bars, 1));
    }

    @Test
    void marketCapFilterCanDisable() {
        props.setMarketCapFilterEnabled(false);
        Map<String, BigDecimal> map = new HashMap<String, BigDecimal>();
        map.put("600036", new BigDecimal("0.01"));
        props.setFloatSharesYi(map);
        List<BarDTO> bars = dayBars("10", "10", "10.10", "10.10");
        bars.get(0).setCode("600036");
        bars.get(1).setCode("600036");
        assertTrue(filter.canOpen("600036", bars, 1));
    }

    private List<BarDTO> dayBars(String c0, String v0, String c1, String v1) {
        List<BarDTO> bars = new ArrayList<BarDTO>();
        bars.add(bar("600036", LocalDateTime.of(2026, 1, 2, 15, 0), c0, v0));
        bars.add(bar("600036", LocalDateTime.of(2026, 1, 3, 15, 0), c1, v1));
        return bars;
    }

    private static BarDTO bar(String code, LocalDateTime t, String close, String vol) {
        BigDecimal c = new BigDecimal(close);
        return BarDTO.builder()
                .code(code).barBegin(t)
                .open(c).high(c).low(c).close(c)
                .volume(new BigDecimal(vol))
                .build();
    }
}
