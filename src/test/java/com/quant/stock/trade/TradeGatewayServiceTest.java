package com.quant.stock.trade;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.trade.dto.OrderDTO;
import com.quant.stock.util.RedisLockUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TradeGatewayServiceTest {

    private TradeGatewayService simGw;
    private TradeGatewayService sdkGw;

    @BeforeEach
    void setUp() {
        RedisLockUtil lock = new RedisLockUtil();
        ObjectProvider<LiveLedgerService> noLedger = new ObjectProvider<LiveLedgerService>() {
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
        QuantProperties sim = new QuantProperties();
        sim.setTradeMode("sim");
        simGw = new TradeGatewayService(lock, sim, noLedger);
        QuantProperties sdk = new QuantProperties();
        sdk.setTradeMode("sdk");
        sdkGw = new TradeGatewayService(lock, sdk, noLedger);
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
}
