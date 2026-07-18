package com.quant.stock.trade;

import cn.hutool.core.util.IdUtil;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.trade.dto.OrderDTO;
import com.quant.stock.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易网关：下单幂等 + 订单状态机。
 * {@code quant.trade-mode=sim}：即时 FILLED；{@code sdk}：SUBMITTED，由 {@link #syncOrderStatus()} 推进（桩）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeGatewayService {

    private final RedisLockUtil redisLockUtil;
    private final QuantProperties props;

    private final Map<String, Integer> positions = new ConcurrentHashMap<String, Integer>();
    private final Map<String, OrderDTO> orders = new ConcurrentHashMap<String, OrderDTO>();
    private final Map<String, String> idempotentIndex = new ConcurrentHashMap<String, String>();

    public OrderDTO placeOrder(String stockCode, OrderDTO.Side side, BigDecimal price, int volume) {
        return placeOrder(stockCode, side, price, volume, null);
    }

    public OrderDTO placeOrder(String stockCode, OrderDTO.Side side, BigDecimal price, int volume,
                               String clientOrderId) {
        final String cid = clientOrderId == null || clientOrderId.trim().isEmpty()
                ? "AUTO-" + IdUtil.fastSimpleUUID() : clientOrderId.trim();

        if (idempotentIndex.containsKey(cid)) {
            String existId = idempotentIndex.get(cid);
            log.warn("幂等拒绝重复下单 clientOrderId={} -> {}", cid, existId);
            return orders.get(existId);
        }

        return redisLockUtil.executeWithLock("order:" + cid, 30, new java.util.function.Supplier<OrderDTO>() {
            @Override
            public OrderDTO get() {
                if (idempotentIndex.containsKey(cid)) {
                    return orders.get(idempotentIndex.get(cid));
                }
                boolean sim = !"sdk".equalsIgnoreCase(props.getTradeMode());
                OrderDTO order = OrderDTO.builder()
                        .stockCode(stockCode)
                        .side(side)
                        .price(price)
                        .volume(volume)
                        .clientOrderId(cid)
                        .status(sim ? OrderDTO.Status.PENDING : OrderDTO.Status.SUBMITTED)
                        .build();
                String orderId = placeOrderSdk(order);
                order.setOrderId(orderId);
                // 本地持仓账本即时更新；sdk 模式状态先 SUBMITTED，由 sync 确认 FILLED
                order.setStatus(sim ? OrderDTO.Status.FILLED : OrderDTO.Status.SUBMITTED);
                applyPosition(side, stockCode, volume);
                orders.put(orderId, order);
                idempotentIndex.put(cid, orderId);
                return order;
            }
        });
    }

    protected String placeOrderSdk(OrderDTO order) {
        String orderId = "SIM-" + IdUtil.fastSimpleUUID();
        log.info("模拟下单成功 orderId={} clientId={} {} {}@{} x{}",
                orderId, order.getClientOrderId(), order.getSide(),
                order.getStockCode(), order.getPrice(), order.getVolume());
        return orderId;
    }

    public Map<String, Integer> queryPositions() {
        return Collections.unmodifiableMap(positions);
    }

    public OrderDTO queryOrder(String orderId) {
        return orders.get(orderId);
    }

    /**
     * sdk 模式：将 SUBMITTED 推进为 FILLED（持仓已在下单时记入本地账本；真实环境应查券商对账）。
     */
    public void syncOrderStatus() {
        int advanced = 0;
        for (OrderDTO order : orders.values()) {
            if (order == null || order.getStatus() != OrderDTO.Status.SUBMITTED) {
                continue;
            }
            order.setStatus(OrderDTO.Status.FILLED);
            advanced++;
        }
        if (advanced > 0) {
            log.info("同步委托：推进 SUBMITTED→FILLED {} 笔", advanced);
        } else {
            log.debug("同步委托状态, 当前委托数={}", orders.size());
        }
    }

    private void applyPosition(OrderDTO.Side side, String stockCode, int volume) {
        int cur = positions.getOrDefault(stockCode, 0);
        if (side == OrderDTO.Side.BUY) {
            positions.put(stockCode, cur + volume);
        } else {
            positions.put(stockCode, Math.max(0, cur - volume));
        }
    }
}
