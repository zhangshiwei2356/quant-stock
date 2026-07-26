package com.quant.stock.portfolio;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioCorrelationMonitorTest {

    @Test
    void identicalSeriesNearPerfectCorrelation() {
        List<BigDecimal> a = series(10, "1.01");
        Map<String, List<BigDecimal>> m = new LinkedHashMap<String, List<BigDecimal>>();
        m.put("A", a);
        m.put("B", new ArrayList<BigDecimal>(a));
        Map<String, Object> r = PortfolioCorrelationMonitor.report(m, 20, new BigDecimal("0.75"));
        assertEquals(1, r.get("pairCount"));
        BigDecimal avg = (BigDecimal) r.get("avgCorrelation");
        assertTrue(avg.compareTo(new BigDecimal("0.99")) >= 0);
        assertTrue(Boolean.TRUE.equals(r.get("warn")));
    }

    @Test
    void singleSymbolSkips() {
        Map<String, List<BigDecimal>> m = new LinkedHashMap<String, List<BigDecimal>>();
        m.put("A", series(20, "1.01"));
        Map<String, Object> r = PortfolioCorrelationMonitor.report(m, 20, new BigDecimal("0.75"));
        assertEquals(0, r.get("pairCount"));
        assertEquals(false, r.get("warn"));
    }

    private static List<BigDecimal> series(int n, String mult) {
        List<BigDecimal> list = new ArrayList<BigDecimal>();
        BigDecimal p = new BigDecimal("10");
        BigDecimal m = new BigDecimal(mult);
        for (int i = 0; i < n; i++) {
            list.add(p);
            p = p.multiply(m).setScale(4, java.math.RoundingMode.HALF_UP);
        }
        return list;
    }
}
