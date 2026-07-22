package com.quant.stock.market;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockFiveMinuteBarsTest {

    @Test
    void generateMockBarsAreFiveMinuteSteps() {
        QuantProperties props = new QuantProperties();
        props.setMockBarDays(2);
        MarketDataService svc = new MarketDataService(props, new JsonBarDataStore(), new NoopKlineSdkClient());
        List<BarDTO> bars = svc.generateMockBars("600036", 1);
        assertTrue(bars.size() >= 40);
        LocalDateTime a = bars.get(0).getBarBegin();
        LocalDateTime b = bars.get(1).getBarBegin();
        assertEquals(5, Duration.between(a, b).toMinutes());
        assertEquals(a.plusMinutes(5), bars.get(0).getBarEnd());
    }
}
