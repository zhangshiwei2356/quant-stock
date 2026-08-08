package com.quant.stock.kuangrui;

import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KuangruiStaticInfoServiceTest {

    @Test
    void applyDisabled_returnsNullLookups() {
        QuantProperties p = new QuantProperties();
        p.getKuangrui().setEnabled(true);
        p.getKuangrui().setStaticEnabled(false);
        KuangruiStaticInfoService svc = new KuangruiStaticInfoService(
                p, new NoopOesReadonlyService(), new NoopMdsMinuteIngestService());
        assertFalse(svc.isApplyEnabled());
        assertNull(svc.isSuspended("600036"));
        assertNull(svc.commissionRate());
        assertNull(svc.exchangeTradingDay());
        assertEquals(Boolean.FALSE, svc.status().get("applyEnabled"));
    }

    @Test
    void applyEnabled_withStubOes_usesCommissionAndDay() {
        QuantProperties p = new QuantProperties();
        p.getKuangrui().setEnabled(true);
        p.getKuangrui().setStaticEnabled(true);
        OesReadonlyService oes = new StubOes();
        KuangruiStaticInfoService svc = new KuangruiStaticInfoService(
                p, oes, new NoopMdsMinuteIngestService());
        assertTrue(svc.isApplyEnabled());
        assertEquals(LocalDateParse("2026-08-06"), svc.exchangeTradingDay());
        assertEquals(0, new BigDecimal("0.0003").compareTo(svc.commissionRate()));
        Map<String, Object> stock = svc.stockStatic("600036");
        assertEquals("600036", stock.get("code"));
        assertEquals(Boolean.TRUE, stock.get("suspended"));
        assertEquals(new BigDecimal("11.0000"), stock.get("upperLimit"));
    }

    private static java.time.LocalDate LocalDateParse(String s) {
        return java.time.LocalDate.parse(s);
    }

    /** 最小 stub：M4 查询返回固定值。 */
    private static final class StubOes implements OesReadonlyService {
        @Override
        public boolean isLive() {
            return true;
        }

        @Override
        public Map<String, Object> status() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("live", true);
            m.put("impl", "stub");
            return m;
        }

        @Override
        public boolean ensureReady() {
            return true;
        }

        @Override
        public List<Map<String, Object>> queryCash() {
            return Collections.emptyList();
        }

        @Override
        public List<Map<String, Object>> queryHoldings() {
            return Collections.emptyList();
        }

        @Override
        public List<Map<String, Object>> queryOrders() {
            return Collections.emptyList();
        }

        @Override
        public List<Map<String, Object>> queryTrades() {
            return Collections.emptyList();
        }

        @Override
        public Map<String, Object> snapshot() {
            return status();
        }

        @Override
        public List<Map<String, Object>> queryStock(String code) {
            return Collections.singletonList(OesViewMapper.stock(
                    code, "stub", 110000L, 90000L, 100000L, 0L, 0L, 1, 0));
        }

        @Override
        public Map<String, Object> queryTradingDay() {
            return OesViewMapper.tradingDay(20260806);
        }

        @Override
        public List<Map<String, Object>> queryCommissionRate() {
            return Collections.singletonList(
                    OesViewMapper.commission(1, 1, 30000L, 50000L, null));
        }

        @Override
        public Map<String, Object> queryClientOverview() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", true);
            m.put("clientId", 1);
            return m;
        }

        @Override
        public List<Map<String, Object>> queryInvAcct() {
            return Collections.emptyList();
        }

        @Override
        public List<Map<String, Object>> queryCounterCash(String cashAcctId) {
            return Collections.emptyList();
        }

        @Override
        public Map<String, Object> queryMaxTradableQty(String code, String side, java.math.BigDecimal priceYuan) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", true);
            m.put("code", code);
            m.put("maxTradableQty", 1000L);
            return m;
        }

        @Override
        public List<Map<String, Object>> queryCashTransferSerial(String cashAcctId) {
            return Collections.emptyList();
        }

        @Override
        public void stop() {
            // no-op
        }

        @Override
        public Map<String, Object> probeLogon(String username, String password) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", true);
            m.put("message", "stub");
            return m;
        }
    }
}
