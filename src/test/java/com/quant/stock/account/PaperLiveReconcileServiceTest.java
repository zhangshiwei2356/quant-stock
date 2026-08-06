package com.quant.stock.account;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.trade.TradeCostModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperLiveReconcileServiceTest {

    @Test
    void flagsSameDayFillAndBuildsCostRow() {
        QuantProperties props = new QuantProperties();
        props.setNextBarOpenFill(true);
        props.setTradeMode("sim");
        AccountOverviewService overview = mock(AccountOverviewService.class);
        List<Map<String, Object>> orders = new ArrayList<Map<String, Object>>();
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("orderId", "t1");
        o.put("code", "600036");
        o.put("side", "BUY");
        o.put("status", "FILLED");
        o.put("price", new BigDecimal("10.00"));
        o.put("filledPrice", new BigDecimal("10.05"));
        o.put("volume", 1000);
        o.put("filledVolume", 1000);
        o.put("fee", new BigDecimal("5.00"));
        o.put("signalDate", "2026-07-20");
        o.put("executionDate", "2026-07-20");
        orders.add(o);
        when(overview.orders()).thenReturn(orders);
        when(overview.positions()).thenReturn(new ArrayList<Map<String, Object>>());

        @SuppressWarnings("unchecked")
        ObjectProvider<com.quant.stock.pool.TradePoolService> poolProvider = mock(ObjectProvider.class);
        when(poolProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.quant.stock.kuangrui.OesReadonlyService> oesProvider = mock(ObjectProvider.class);
        when(oesProvider.getIfAvailable()).thenReturn(null);

        TradeCostModel costModel = new TradeCostModel(props);
        SlippageResidualService slip = new SlippageResidualService(overview, costModel, props);
        PaperLiveReconcileService svc = new PaperLiveReconcileService(
                props, costModel, overview, slip, poolProvider, oesProvider);
        Map<String, Object> report = svc.report();
        assertNotNull(report.get("configFingerprint"));
        assertFalse(Boolean.TRUE.equals(report.get("gatePass")));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertEquals(1, summary.get("sameDayFillVsNextBar"));
        assertNotNull(summary.get("avgAbsAdverseBps"));
        assertNotNull(report.get("slippageResidual"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> costRows = (List<Map<String, Object>>) report.get("costRows");
        assertEquals(1, costRows.size());
        assertTrue(costRows.get(0).containsKey("feeResidual"));
    }
}
