package com.quant.stock.risk;

import com.quant.stock.calendar.TradingCalendar;
import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalDriftMonitorTest {

    private SignalDriftMonitor monitor;
    private StrategyRetirementService retirement;

    @BeforeEach
    void setUp() {
        QuantProperties props = new QuantProperties();
        props.setSignalDriftEnabled(true);
        props.setDriftLookbackRounds(5);
        props.setDriftMinWinRate(new BigDecimal("0.40"));
        props.setDriftConfirmRounds(2);
        props.setAutoRetireOnSignalDrift(true);
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
        RiskAlertService alerts = new RiskAlertService(props, empty);
        retirement = new StrategyRetirementService(props, new TradingCalendar());
        monitor = new SignalDriftMonitor(props, alerts, retirement);
        monitor.clearForTests();
    }

    @Test
    void lowWinRateCanRetireAfterConfirm() {
        LocalDate d = LocalDate.of(2026, 7, 1);
        for (int i = 0; i < 5; i++) {
            monitor.onClosedRound(false, d.plusDays(i));
        }
        // first evaluation after 5 samples: streak=1; second closed round re-eval: need another full window
        for (int i = 0; i < 5; i++) {
            monitor.onClosedRound(false, d.plusDays(10 + i));
        }
        assertTrue(Boolean.TRUE.equals(monitor.status().get("killArmed"))
                || retirement.isRetired());
        assertEquals(true, retirement.isRetired() || Boolean.TRUE.equals(monitor.status().get("killArmed")));
    }

    @Test
    void statusDeclaresProxyIcBoundary() {
        assertEquals("MA_SPREAD_PROXY", monitor.status().get("icSource"));
        assertEquals("UNAVAILABLE", monitor.status().get("factorIcStatus"));
    }
}
