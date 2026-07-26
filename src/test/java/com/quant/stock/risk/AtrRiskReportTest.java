package com.quant.stock.risk;

import com.quant.stock.backtest.DecisionAnalysisLog;
import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtrRiskReportTest {

    @Test
    void snapshotContainsContractAndStopCount() {
        QuantProperties p = new QuantProperties();
        DecisionAnalysisLog log = new DecisionAnalysisLog();
        log.stop("600000", LocalDateTime.now(), "止损触及", Collections.<String, Object>emptyMap());
        Map<String, Object> m = AtrRiskReport.from(p, log);
        assertEquals(new BigDecimal("2.0"), m.get("atrStopMultiplier"));
        assertEquals(new BigDecimal("0.2"), m.get("atrAdjustClampMin"));
        assertEquals(new BigDecimal("1.5"), m.get("atrAdjustClampMax"));
        assertEquals(1, m.get("stopExitEvents"));
        assertTrue(String.valueOf(m.get("contract")).contains("ATR"));
    }
}
