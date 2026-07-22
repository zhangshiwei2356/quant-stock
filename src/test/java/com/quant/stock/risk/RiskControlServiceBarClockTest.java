package com.quant.stock.risk;

import com.quant.stock.calendar.TradingCalendar;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.util.PositionAmountUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskControlServiceBarClockTest {

    private RiskControlService risk;

    @BeforeEach
    void setUp() {
        QuantProperties props = new QuantProperties();
        props.setQuietOpenEnabled(false);
        props.setQuietCloseEnabled(false);
        props.setMarketCapFilterEnabled(false);
        props.setMinAvgVolume20(1L);
        OpenFilterService openFilter = new OpenFilterService(props);
        risk = new RiskControlService(
                props,
                new PositionAmountUtil(props),
                openFilter,
                new LiveAccountRiskState(props),
                new TradingCalendar());
    }

    @Test
    void checkBuyUsesBarClockNotWallClock() {
        // 模拟历史交易时段 K 线（即使当前墙钟是深夜，也应按 bar 时间放行）
        List<BarDTO> bars = new ArrayList<BarDTO>();
        bars.add(bar(LocalDateTime.of(2026, 3, 10, 10, 0), "10.00"));
        bars.add(bar(LocalDateTime.of(2026, 3, 10, 10, 5), "10.10"));
        boolean ok = risk.checkBuy("600036", new BigDecimal("10.10"), 100,
                new BigDecimal("100000"), BigDecimal.ZERO, Collections.<String, Integer>emptyMap(),
                bars, 1);
        assertTrue(ok);
    }

    @Test
    void checkBuyRejectsNonTradingBarTime() {
        List<BarDTO> bars = new ArrayList<BarDTO>();
        bars.add(bar(LocalDateTime.of(2026, 3, 10, 10, 0), "10.00"));
        bars.add(bar(LocalDateTime.of(2026, 3, 10, 20, 0), "10.10")); // 夜盘非交易
        boolean ok = risk.checkBuy("600036", new BigDecimal("10.10"), 100,
                new BigDecimal("100000"), BigDecimal.ZERO, Collections.<String, Integer>emptyMap(),
                bars, 1);
        assertFalse(ok);
    }

    @Test
    void checkSellUsesBarClock() {
        List<BarDTO> bars = new ArrayList<BarDTO>();
        bars.add(bar(LocalDateTime.of(2026, 3, 10, 10, 0), "10.00"));
        Map<String, Integer> pos = Collections.singletonMap("600036", 100);
        assertTrue(risk.checkSell("600036", 100, pos, bars, 0));
        bars.add(bar(LocalDateTime.of(2026, 3, 10, 20, 0), "10.00"));
        assertFalse(risk.checkSell("600036", 100, pos, bars, 1));
    }

    private static BarDTO bar(LocalDateTime t, String close) {
        BigDecimal c = new BigDecimal(close);
        return BarDTO.builder()
                .code("600036")
                .barBegin(t)
                .open(c).high(c).low(c).close(c)
                .volume(new BigDecimal("100000"))
                .build();
    }
}
