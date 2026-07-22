package com.quant.stock.trade;

import cn.hutool.core.util.IdUtil;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.trade.dto.OrderDTO;
import com.quant.stock.util.RedisLockUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易网关：下单幂等 + 订单状态机。
 * {@code quant.trade-mode=sim}：即时 FILLED 并改仓；
 * {@code sdk}：SUBMITTED 不改仓，由 {@link #syncOrderStatus()} 推进 FILLED 后再改仓。
 */
@Slf4j
@Service
public class TradeGatewayService {

    private final RedisLockUtil redisLockUtil;
    private final QuantProperties props;
    private final ObjectProvider<LiveLedgerService> liveLedgerProvider;

    private final Map<String, Integer> positions = new ConcurrentHashMap<String, Integer>();
    private final Map<String, OrderDTO> orders = new ConcurrentHashMap<String, OrderDTO>();
    private final Map<String, String> idempotentIndex = new ConcurrentHashMap<String, String>();

    public TradeGatewayService(RedisLockUtil redisLockUtil,
                               QuantProperties props,
                               ObjectProvider<LiveLedgerService> liveLedgerProvider) {
        this.redisLockUtil = redisLockUtil;
        this.props = props;
        this.liveLedgerProvider = liveLedgerProvider;
    }

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
                if (sim) {
                    order.setStatus(OrderDTO.Status.FILLED);
                    order.setFilledVolume(volume);
                    applyPosition(side, stockCode, volume);
                } else {
                    // sdk：已报未成，仓位待 sync 确认
                    order.setStatus(OrderDTO.Status.SUBMITTED);
                    order.setFilledVolume(0);
                }
                orders.put(orderId, order);
                idempotentIndex.put(cid, orderId);
                persistOrder(order, null, null);
                return order;
            }
        });
    }

    /**
     * 撤销未完结委托（SUBMITTED / PARTIAL）。已成交部分不回滚。
     *
     * @return 撤销后的委托；不可撤或不存在时返回 null
     */
    public OrderDTO cancelOrder(String orderId) {
        if (orderId == null || orderId.trim().isEmpty()) {
            return null;
        }
        OrderDTO order = orders.get(orderId.trim());
        if (order == null) {
            return null;
        }
        OrderDTO.Status st = order.getStatus();
        if (st != OrderDTO.Status.SUBMITTED && st != OrderDTO.Status.PARTIAL) {
            log.warn("不可撤单 orderId={} status={}", orderId, st);
            return null;
        }
        order.setStatus(OrderDTO.Status.CANCELLED);
        if (order.getFilledVolume() == null) {
            order.setFilledVolume(0);
        }
        persistOrder(order, null, null);
        log.info("撤单成功 orderId={} filled={}", orderId, order.getFilledVolume());
        return order;
    }

    /**
     * 本地部成桩：对 SUBMITTED/PARTIAL 追加成交量并改仓；满量则 FILLED。
     *
     * @param fillQty 本笔追加成交量（须为 100 整数倍）
     */
    public OrderDTO applyPartialFill(String orderId, int fillQty) {
        if (orderId == null || fillQty < 100 || fillQty % 100 != 0) {
            return null;
        }
        OrderDTO order = orders.get(orderId.trim());
        if (order == null) {
            return null;
        }
        OrderDTO.Status st = order.getStatus();
        if (st != OrderDTO.Status.SUBMITTED && st != OrderDTO.Status.PARTIAL) {
            return null;
        }
        int vol = order.getVolume() == null ? 0 : order.getVolume();
        int filled = order.getFilledVolume() == null ? 0 : order.getFilledVolume();
        int remain = vol - filled;
        if (remain <= 0) {
            order.setStatus(OrderDTO.Status.FILLED);
            order.setFilledVolume(vol);
            persistOrder(order, null, null);
            return order;
        }
        int delta = Math.min(fillQty, remain);
        applyPosition(order.getSide(), order.getStockCode(), delta);
        filled += delta;
        order.setFilledVolume(filled);
        order.setStatus(filled >= vol ? OrderDTO.Status.FILLED : OrderDTO.Status.PARTIAL);
        persistOrder(order, null, null);
        log.info("部成 orderId={} +{} → filled={}/{} status={}",
                orderId, delta, filled, vol, order.getStatus());
        return order;
    }

    /** 启动恢复：用持久化持仓覆盖网关数量账本（不产生委托）。 */
    public void restorePositionQty(String stockCode, int volume) {
        if (stockCode == null || stockCode.trim().isEmpty()) {
            return;
        }
        if (volume <= 0) {
            positions.remove(stockCode);
        } else {
            positions.put(stockCode, volume);
        }
    }

    /** 策略成交后可补写费用与信号日 */
    public void persistOrder(OrderDTO order, LocalDate signalDate, BigDecimal fee) {
        LiveLedgerService ledger = liveLedgerProvider.getIfAvailable();
        if (ledger == null || order == null) {
            return;
        }
        ledger.upsertOrder(order, signalDate, fee);
    }

    protected String placeOrderSdk(OrderDTO order) {
        // 控制在 VARCHAR(32) 内：S + 31 位 hex
        String u = IdUtil.fastSimpleUUID();
        String orderId = "S" + (u.length() > 31 ? u.substring(0, 31) : u);
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

    /** 内存委托列表（按 orderId 倒序，最近在前） */
    public List<OrderDTO> listOrders() {
        List<OrderDTO> list = new ArrayList<OrderDTO>(orders.values());
        Collections.sort(list, new Comparator<OrderDTO>() {
            @Override
            public int compare(OrderDTO a, OrderDTO b) {
                String ia = a == null || a.getOrderId() == null ? "" : a.getOrderId();
                String ib = b == null || b.getOrderId() == null ? "" : b.getOrderId();
                return ib.compareTo(ia);
            }
        });
        return list;
    }

    /**
     * sdk 模式：SUBMITTED → FILLED，改本地仓位并回写 DB。
     *
     * @return 本轮新成交的委托列表
     * <p>
     * TODO(api): 对接券商委托查询 / 成交回报；当前为本地桩。
     */
    public List<OrderDTO> syncOrderStatus() {
        List<OrderDTO> advanced = new ArrayList<OrderDTO>();
        for (OrderDTO order : orders.values()) {
            if (order == null) {
                continue;
            }
            OrderDTO.Status st = order.getStatus();
            if (st != OrderDTO.Status.SUBMITTED && st != OrderDTO.Status.PARTIAL) {
                continue;
            }
            order.setStatus(OrderDTO.Status.FILLED);
            int vol = order.getVolume() == null ? 0 : order.getVolume();
            int filled = order.getFilledVolume() == null ? 0 : order.getFilledVolume();
            int remain = Math.max(0, vol - filled);
            if (remain > 0) {
                applyPosition(order.getSide(), order.getStockCode(), remain);
            }
            order.setFilledVolume(vol);
            persistOrder(order, null, null);
            advanced.add(order);
        }
        if (!advanced.isEmpty()) {
            log.info("同步委托：推进未完结→FILLED {} 笔", advanced.size());
        } else {
            log.debug("同步委托状态, 当前委托数={}", orders.size());
        }
        return advanced;
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
