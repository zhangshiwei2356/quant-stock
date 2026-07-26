package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IcDecayMonitorTest {

    private IcDecayMonitor monitor;

    @BeforeEach
    void setUp() {
        QuantProperties props = new QuantProperties();
        props.setIcDecayEnabled(true);
        props.setIcDecayLookback(40);
        props.setIcDecayMinHalfLifeBars(5);
        props.setIcDecayMinIr(new BigDecimal("0.10"));
        ObjectProvider<RiskControlLogService> empty = new ObjectProvider<RiskControlLogService>() {
            @Override
            public RiskControlLogService getObject() {
                return null;
            }

            @Override
            public RiskControlLogService getObject(Object... args) {
                return null;
            }

            @Override
            public RiskControlLogService getIfAvailable() {
                return null;
            }

            @Override
            public RiskControlLogService getIfUnique() {
                return null;
            }
        };
        monitor = new IcDecayMonitor(props, new RiskAlertService(props, empty));
        monitor.clearForTests();
    }

    @Test
    void estimateHalfLifeFromPeak() {
        List<BigDecimal> series = Arrays.asList(
                bd("0.10"), bd("0.40"), bd("0.30"), bd("0.20"), bd("0.18"), bd("0.15")
        );
        BigDecimal hl = IcDecayMonitor.estimateHalfLife(series);
        assertNotNull(hl);
        // peak at index1=0.40; half=0.20 first hit at index3 → 2 bars
        assertEquals(0, BigDecimal.valueOf(2).compareTo(hl));
    }

    @Test
    void decayScalesPosition() {
        LocalDate d = LocalDate.of(2026, 7, 1);
        // 喂入衰减序列：峰值后快速跌破半值 + 均值近零 → IR 差
        BigDecimal[] samples = {
                bd("0.50"), bd("0.45"), bd("0.20"), bd("0.10"), bd("0.05"),
                bd("0.02"), bd("0.01"), bd("-0.01"), bd("0.00"), bd("0.01")
        };
        for (BigDecimal s : samples) {
            monitor.onIcSample(d, s);
        }
        assertTrue(monitor.isDecayActive() || monitor.positionScaleMultiplier().compareTo(BigDecimal.ONE) < 0
                        || monitor.status().get("halfLifeBars") != null,
                "应能估计半衰期或触发衰减");
        // 再喂极差 IR 序列确保降仓
        for (int i = 0; i < 20; i++) {
            monitor.onIcSample(d, i % 2 == 0 ? bd("0.02") : bd("-0.02"));
        }
        assertEquals(0, new BigDecimal("0.5").compareTo(monitor.positionScaleMultiplier()));
        assertTrue(monitor.isDecayActive());
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}
