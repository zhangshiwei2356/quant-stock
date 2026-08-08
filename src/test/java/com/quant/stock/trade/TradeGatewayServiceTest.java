package com.quant.stock.trade;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.kuangrui.OesOrderService;
import com.quant.stock.trade.dto.OrderDTO;
import com.quant.stock.util.RedisLockUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeGatewayServiceTest {

    private TradeGatewayService simGw;
    private TradeGatewayService sdkGw;

    @BeforeEach
    void setUp() {
        RedisLockUtil lock = new RedisLockUtil();
        ObjectProvider<LiveLedgerService> noLedger = emptyLedger();
        ObjectProvider<OesOrderService> noOes = emptyOes();
        QuantProperties sim = new QuantProperties();
        sim.setTradeMode("sim");
        simGw = new TradeGatewayService(lock, sim, noLedger, noOes);
        QuantProperties sdk = new QuantProperties();
        sdk.setTradeMode("sdk");
        sdkGw = new TradeGatewayService(lock, sdk, noLedger, noOes);
    }

    @Test
    void simFillsImmediately() {
        OrderDTO o = simGw.placeOrder("600036", OrderDTO.Side.BUY, new BigDecimal("10"), 100, "c1");
        assertEquals(OrderDTO.Status.FILLED, o.getStatus());
        assertEquals(100, simGw.queryPositions().get("600036").intValue());
    }

    @Test
    void sdkSubmittedThenSyncFills() {
        OrderDTO o = sdkGw.placeOrder("600036", OrderDTO.Side.BUY, new BigDecimal("10"), 100, "c2");
        assertEquals(OrderDTO.Status.SUBMITTED, o.getStatus());
        assertNull(sdkGw.queryPositions().get("600036"));
        assertEquals(1, sdkGw.syncOrderStatus().size());
        assertEquals(OrderDTO.Status.FILLED, sdkGw.queryOrder(o.getOrderId()).getStatus());
        assertEquals(100, sdkGw.queryPositions().get("600036").intValue());
        assertEquals(0, sdkGw.syncOrderStatus().size());
    }

    @Test
    void sdkCancelReleasesWithoutPosition() {
        OrderDTO o = sdkGw.placeOrder("600036", OrderDTO.Side.BUY, new BigDecimal("10"), 100, "c3");
        assertEquals(OrderDTO.Status.SUBMITTED, o.getStatus());
        OrderDTO cancelled = sdkGw.cancelOrder(o.getOrderId());
        assertEquals(OrderDTO.Status.CANCELLED, cancelled.getStatus());
        assertNull(sdkGw.queryPositions().get("600036"));
        assertEquals(0, sdkGw.syncOrderStatus().size());
    }

    @Test
    void sdkPartialThenSyncFillsRemain() {
        OrderDTO o = sdkGw.placeOrder("600036", OrderDTO.Side.BUY, new BigDecimal("10"), 300, "c4");
        OrderDTO part = sdkGw.applyPartialFill(o.getOrderId(), 100);
        assertEquals(OrderDTO.Status.PARTIAL, part.getStatus());
        assertEquals(100, part.getFilledVolume().intValue());
        assertEquals(100, sdkGw.queryPositions().get("600036").intValue());
        assertEquals(1, sdkGw.syncOrderStatus().size());
        assertEquals(OrderDTO.Status.FILLED, sdkGw.queryOrder(o.getOrderId()).getStatus());
        assertEquals(300, sdkGw.queryPositions().get("600036").intValue());
    }

    @Test
    void clientOrderIdIdempotent() {
        OrderDTO a = simGw.placeOrder("600036", OrderDTO.Side.BUY, new BigDecimal("10"), 100, "same");
        OrderDTO b = simGw.placeOrder("600036", OrderDTO.Side.BUY, new BigDecimal("10"), 100, "same");
        assertSame(a, b);
        assertEquals(100, simGw.queryPositions().get("600036").intValue());
    }

    @Test
    void oesLive_placeCancelAndSyncFillFromEvents() {
        final ConcurrentLinkedQueue<OesOrderService.OesOrderEvent> q =
                new ConcurrentLinkedQueue<OesOrderService.OesOrderEvent>();
        OesOrderService fake = new OesOrderService() {
            @Override
            public boolean isOrderLive() {
                return true;
            }

            @Override
            public Map<String, Object> status() {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("orderLive", true);
                m.put("impl", "fake");
                return m;
            }

            @Override
            public OesPlaceResult placeLimit(String stockCode, OrderDTO.Side side, BigDecimal priceYuan,
                                             int qty, int clSeqNo, String clientOrderId) {
                return OesPlaceResult.ok(clSeqNo, 1000L + clSeqNo);
            }

            @Override
            public boolean cancelByClSeqNo(int origClSeqNo, String stockCode) {
                return true;
            }

            @Override
            public OesPlaceResult sendCashTrsf(int clSeqNo, String direct, BigDecimal amountYuan,
                                               String cashAcctId, String trsfType,
                                               String trdPasswd, String trsfPasswd) {
                return OesPlaceResult.fail(clSeqNo, "fake 不支持银证");
            }

            @Override
            public List<OesOrderEvent> pollEvents() {
                List<OesOrderEvent> out = new ArrayList<OesOrderEvent>();
                OesOrderEvent e;
                while ((e = q.poll()) != null) {
                    out.add(e);
                }
                return out;
            }
        };
        RedisLockUtil lock = new RedisLockUtil();
        QuantProperties sdk = new QuantProperties();
        sdk.setTradeMode("sdk");
        TradeGatewayService gw = new TradeGatewayService(lock, sdk, emptyLedger(), providerOf(fake));

        OrderDTO o = gw.placeOrder("600036", OrderDTO.Side.BUY, new BigDecimal("10"), 100, "oes1");
        assertEquals(OrderDTO.Status.SUBMITTED, o.getStatus());
        assertTrue(o.getOrderId().startsWith("O"));
        assertNull(gw.queryPositions().get("600036"));

        q.offer(new OesOrderService.OesOrderEvent(
                OesOrderService.OesOrderEvent.Kind.ORDER,
                1, 1001L, "600036", 8, 100, 0, null));
        assertEquals(1, gw.syncOrderStatus().size());
        assertEquals(OrderDTO.Status.FILLED, gw.queryOrder(o.getOrderId()).getStatus());
        assertEquals(100, gw.queryPositions().get("600036").intValue());

        OrderDTO o2 = gw.placeOrder("600036", OrderDTO.Side.SELL, new BigDecimal("10"), 100, "oes2");
        assertEquals(OrderDTO.Status.SUBMITTED, o2.getStatus());
        OrderDTO cancelSent = gw.cancelOrder(o2.getOrderId());
        // 确认制：OES live 仅发撤，本地状态不变
        assertEquals(OrderDTO.Status.SUBMITTED, cancelSent.getStatus());
        q.offer(new OesOrderService.OesOrderEvent(
                OesOrderService.OesOrderEvent.Kind.ORDER,
                2, 1002L, "600036", 7, 0, 0, null));
        gw.syncOrderStatus();
        assertEquals(OrderDTO.Status.CANCELLED, gw.queryOrder(o2.getOrderId()).getStatus());

        // PARTIALLY_CANCELED(6) → 本地 CANCELLED（保留已成量）
        OrderDTO o3 = gw.placeOrder("600036", OrderDTO.Side.BUY, new BigDecimal("10"), 200, "oes3");
        q.offer(new OesOrderService.OesOrderEvent(
                OesOrderService.OesOrderEvent.Kind.ORDER,
                3, 1003L, "600036", 3, 100, 0, null));
        gw.syncOrderStatus();
        assertEquals(OrderDTO.Status.PARTIAL, gw.queryOrder(o3.getOrderId()).getStatus());
        assertEquals(100, gw.queryOrder(o3.getOrderId()).getFilledVolume().intValue());
        OrderDTO cancelPartial = gw.cancelOrder(o3.getOrderId());
        assertEquals(OrderDTO.Status.PARTIAL, cancelPartial.getStatus());
        q.offer(new OesOrderService.OesOrderEvent(
                OesOrderService.OesOrderEvent.Kind.ORDER,
                3, 1003L, "600036", 6, 100, 0, null));
        gw.syncOrderStatus();
        assertEquals(OrderDTO.Status.CANCELLED, gw.queryOrder(o3.getOrderId()).getStatus());
        assertEquals(100, gw.queryOrder(o3.getOrderId()).getFilledVolume().intValue());
    }

    private static ObjectProvider<LiveLedgerService> emptyLedger() {
        return new ObjectProvider<LiveLedgerService>() {
            @Override
            public LiveLedgerService getObject(Object... args) throws BeansException {
                return null;
            }

            @Override
            public LiveLedgerService getObject() throws BeansException {
                return null;
            }

            @Override
            public LiveLedgerService getIfAvailable() {
                return null;
            }

            @Override
            public LiveLedgerService getIfUnique() {
                return null;
            }
        };
    }

    private static ObjectProvider<OesOrderService> emptyOes() {
        return providerOf(null);
    }

    private static ObjectProvider<OesOrderService> providerOf(final OesOrderService svc) {
        return new ObjectProvider<OesOrderService>() {
            @Override
            public OesOrderService getObject(Object... args) throws BeansException {
                return svc;
            }

            @Override
            public OesOrderService getObject() throws BeansException {
                return svc;
            }

            @Override
            public OesOrderService getIfAvailable() {
                return svc;
            }

            @Override
            public OesOrderService getIfUnique() {
                return svc;
            }
        };
    }
}
