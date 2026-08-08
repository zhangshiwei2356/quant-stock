package com.quant.stock.trade;

import cn.hutool.core.util.IdUtil;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.kuangrui.OesOrderService;
import com.quant.stock.kuangrui.OesViewMapper;
import com.quant.stock.trade.dto.OrderDTO;
import com.quant.stock.util.RedisLockUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 交易网关：下单幂等 + 订单状态机。
 * {@code quant.trade-mode=sim}：即时 FILLED 并改仓；
 * {@code sdk}：SUBMITTED 不改仓，由 {@link #syncOrderStatus()} 推进 FILLED 后再改仓。
 * 若宽睿 OES {@code order-enabled} live：限价报撤走柜台，sync 按回报/查询推进（不再假推进）。
 */
@Slf4j
@Service
public class TradeGatewayService {

    private final RedisLockUtil redisLockUtil;
    private final QuantProperties props;
    private final ObjectProvider<LiveLedgerService> liveLedgerProvider;
    private final ObjectProvider<OesOrderService> oesOrderProvider;

    private final Map<String, Integer> positions = new ConcurrentHashMap<String, Integer>();
    private final Map<String, OrderDTO> orders = new ConcurrentHashMap<String, OrderDTO>();
    private final Map<String, String> idempotentIndex = new ConcurrentHashMap<String, String>();
    /** 本地 orderId → OES clSeqNo */
    private final Map<String, Integer> orderIdToClSeq = new ConcurrentHashMap<String, Integer>();
    private final Map<Integer, String> clSeqToOrderId = new ConcurrentHashMap<Integer, String>();
    private final AtomicInteger clSeqGen = new AtomicInteger(1);

    /**
     * Spring 注入主构造（多构造时必须 {@code @Autowired}，否则会退回无参并启动失败）。
     */
    @Autowired
    public TradeGatewayService(RedisLockUtil redisLockUtil,
                               QuantProperties props,
                               ObjectProvider<LiveLedgerService> liveLedgerProvider,
                               ObjectProvider<OesOrderService> oesOrderProvider) {
        this.redisLockUtil = redisLockUtil;
        this.props = props;
        this.liveLedgerProvider = liveLedgerProvider;
        this.oesOrderProvider = oesOrderProvider;
    }

    /** 兼容旧测试：无 OES 门面时使用。 */
    public TradeGatewayService(RedisLockUtil redisLockUtil,
                               QuantProperties props,
                               ObjectProvider<LiveLedgerService> liveLedgerProvider) {
        this(redisLockUtil, props, liveLedgerProvider, emptyOesProvider());
    }

    /**
     * 下单（自动生成客户端幂等键）。
     */
    public OrderDTO placeOrder(String stockCode, OrderDTO.Side side, BigDecimal price, int volume) {
        return placeOrder(stockCode, side, price, volume, null);
    }

    /**
     * 下单：Redis 锁 + 客户端幂等；sim 即时 FILLED，sdk 为 SUBMITTED。
     *
     * @param clientOrderId 幂等键；空则自动生成
     */
    public OrderDTO placeOrder(String stockCode, OrderDTO.Side side, BigDecimal price, int volume,
                               String clientOrderId) {
        final String cid = clientOrderId == null || clientOrderId.trim().isEmpty()
                ? "AUTO-" + IdUtil.fastSimpleUUID() : clientOrderId.trim();

        if (idempotentIndex.containsKey(cid)) {
            String existId = idempotentIndex.get(cid);
            log.info("幂等拒绝重复下单 clientOrderId={} -> {}", cid, existId);
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
                } else if (order.getStatus() == OrderDTO.Status.REJECTED) {
                    if (order.getFilledVolume() == null) {
                        order.setFilledVolume(0);
                    }
                } else {
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
     * <p>
     * OES live：仅发撤单请求，等回报/查询确认后再置 {@code CANCELLED}（勿乐观假撤）。
     * sim / 无 OES：本地即时撤销。
     * </p>
     *
     * @return 撤销后的委托；不可撤或不存在时返回 null；OES 已发撤但未确认时仍返回原委托（状态未变）
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
            log.info("不可撤单 orderId={} status={}", orderId, st);
            return null;
        }
        OesOrderService oes = oesOrder();
        Integer clSeq = orderIdToClSeq.get(order.getOrderId());
        if (oes != null && oes.isOrderLive() && clSeq != null) {
            boolean sent = oes.cancelByClSeqNo(clSeq.intValue(), order.getStockCode());
            if (!sent) {
                log.error("OES 撤单请求失败 orderId={} clSeqNo={}", orderId, clSeq);
                return null;
            }
            // 确认制：不本地置 CANCELLED；由 syncFromOes 根据状态 5/6/7 收束
            log.info("OES 撤单已发，等待回报确认 orderId={} clSeqNo={} status={}",
                    orderId, clSeq, order.getStatus());
            return order;
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
     * 改价=撤补重置队尾（P0-95）：撤销未完结委托后以新价/新量重新下单（新 orderId，不保队列优先级）。
     *
     * @return 新委托；撤单失败、OES 撤单待确认或参数非法时 null
     */
    public OrderDTO replaceOrder(String orderId, BigDecimal newPrice, Integer newVolume) {
        OrderDTO old = cancelOrder(orderId);
        if (old == null) {
            return null;
        }
        if (old.getStatus() != OrderDTO.Status.CANCELLED) {
            log.error("改价补单：OES 撤单待确认，暂不补单 orderId={} status={}",
                    orderId, old.getStatus());
            return null;
        }
        int remain = old.getVolume() == null ? 0 : old.getVolume();
        int filled = old.getFilledVolume() == null ? 0 : old.getFilledVolume();
        int leftover = Math.max(0, remain - filled);
        int vol = newVolume == null || newVolume <= 0 ? leftover : newVolume;
        vol = (vol / 100) * 100;
        if (vol < 100 || newPrice == null || newPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("改价补单失败：余量或价格非法 orderId={} vol={} px={}", orderId, vol, newPrice);
            return null;
        }
        String cid = "RPL-" + IdUtil.fastSimpleUUID();
        OrderDTO neu = placeOrder(old.getStockCode(), old.getSide(), newPrice, vol, cid);
        log.info("改价撤补 orderId={} → newOrderId={} px={} vol={} (队尾重置)",
                orderId, neu == null ? null : neu.getOrderId(), newPrice, vol);
        return neu;
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

    /** 启动恢复：挂入未完结委托（不改仓、不成交）。 */
    public void restoreOpenOrder(OrderDTO order) {
        if (order == null || order.getOrderId() == null) {
            return;
        }
        orders.put(order.getOrderId(), order);
        if (order.getClientOrderId() != null && !order.getClientOrderId().trim().isEmpty()) {
            idempotentIndex.put(order.getClientOrderId().trim(), order.getOrderId());
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

    /**
     * sdk 下单：OES live 时发限价单；否则本地生成委托号（桩）。
     */
    protected String placeOrderSdk(OrderDTO order) {
        OesOrderService oes = oesOrder();
        if (oes != null && oes.isOrderLive()) {
            int clSeq = clSeqGen.getAndIncrement();
            if (clSeq <= 0) {
                clSeq = clSeqGen.incrementAndGet();
            }
            OesOrderService.OesPlaceResult r = oes.placeLimit(
                    order.getStockCode(), order.getSide(), order.getPrice(),
                    order.getVolume() == null ? 0 : order.getVolume(),
                    clSeq, order.getClientOrderId());
            String orderId = "O" + clSeq;
            if (orderId.length() > 32) {
                orderId = orderId.substring(0, 32);
            }
            if (!r.isAccepted()) {
                order.setStatus(OrderDTO.Status.REJECTED);
                order.setFilledVolume(0);
                log.error("OES 报单拒绝 orderId={} clSeqNo={} msg={}", orderId, clSeq, r.getMessage());
                return orderId;
            }
            orderIdToClSeq.put(orderId, Integer.valueOf(clSeq));
            clSeqToOrderId.put(Integer.valueOf(clSeq), orderId);
            log.info("OES 报单已发 orderId={} clSeqNo={} clOrdId={} {} {}@{} x{}",
                    orderId, clSeq, r.getClOrdId(), order.getSide(),
                    order.getStockCode(), order.getPrice(), order.getVolume());
            return orderId;
        }
        String u = IdUtil.fastSimpleUUID();
        String orderId = "S" + (u.length() > 31 ? u.substring(0, 31) : u);
        log.info("模拟下单成功 orderId={} clientId={} {} {}@{} x{}",
                orderId, order.getClientOrderId(), order.getSide(),
                order.getStockCode(), order.getPrice(), order.getVolume());
        return orderId;
    }

    /** 网关内存持仓快照（只读）。 */
    public Map<String, Integer> queryPositions() {
        return Collections.unmodifiableMap(positions);
    }

    /** 按委托号查询内存委托。 */
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
     * sdk 模式同步：OES live 时按回报/查询推进；否则本地桩 SUBMITTED→FILLED。
     *
     * @return 本轮新成交至 FILLED 的委托列表（供策略落账）
     */
    public List<OrderDTO> syncOrderStatus() {
        OesOrderService oes = oesOrder();
        if (oes != null && oes.isOrderLive()) {
            return syncFromOes(oes);
        }
        return syncStubFillAll();
    }

    private List<OrderDTO> syncFromOes(OesOrderService oes) {
        List<OrderDTO> newlyFilled = new ArrayList<OrderDTO>();
        List<OesOrderService.OesOrderEvent> events = oes.pollEvents();
        for (OesOrderService.OesOrderEvent ev : events) {
            if (ev == null) {
                continue;
            }
            String oid = clSeqToOrderId.get(Integer.valueOf(ev.getClSeqNo()));
            if (oid == null) {
                continue;
            }
            OrderDTO order = orders.get(oid);
            if (order == null) {
                continue;
            }
            OrderDTO.Status before = order.getStatus();
            if (before == OrderDTO.Status.FILLED || before == OrderDTO.Status.CANCELLED
                    || before == OrderDTO.Status.REJECTED) {
                continue;
            }
            if (ev.getKind() == OesOrderService.OesOrderEvent.Kind.TRADE && ev.getTrdQty() > 0) {
                int vol = order.getVolume() == null ? 0 : order.getVolume();
                int filled = order.getFilledVolume() == null ? 0 : order.getFilledVolume();
                int delta = Math.min(ev.getTrdQty(), Math.max(0, vol - filled));
                if (delta > 0) {
                    applyPosition(order.getSide(), order.getStockCode(), delta);
                    filled += delta;
                    order.setFilledVolume(filled);
                    order.setStatus(filled >= vol ? OrderDTO.Status.FILLED : OrderDTO.Status.PARTIAL);
                    if (ev.getTrdPrice() != null) {
                        order.setPrice(ev.getTrdPrice());
                    }
                    persistOrder(order, null, null);
                }
            } else if (ev.getKind() == OesOrderService.OesOrderEvent.Kind.ORDER) {
                String local = OesViewMapper.toLocalStatusName(ev.getOrdStatus());
                int vol = order.getVolume() == null ? 0 : order.getVolume();
                int cum = Math.max(0, ev.getCumQty());
                int prevFilled = order.getFilledVolume() == null ? 0 : order.getFilledVolume();
                if (cum > prevFilled) {
                    int delta = Math.min(cum - prevFilled, Math.max(0, vol - prevFilled));
                    if (delta > 0) {
                        applyPosition(order.getSide(), order.getStockCode(), delta);
                    }
                    order.setFilledVolume(Math.min(cum, vol));
                }
                if ("FILLED".equals(local)) {
                    int filled = order.getFilledVolume() == null ? 0 : order.getFilledVolume();
                    int remain = Math.max(0, vol - filled);
                    if (remain > 0) {
                        applyPosition(order.getSide(), order.getStockCode(), remain);
                        order.setFilledVolume(vol);
                    }
                    order.setStatus(OrderDTO.Status.FILLED);
                } else if ("CANCELLED".equals(local)) {
                    order.setStatus(OrderDTO.Status.CANCELLED);
                } else if ("REJECTED".equals(local)) {
                    order.setStatus(OrderDTO.Status.REJECTED);
                } else if ("PARTIAL".equals(local)) {
                    order.setStatus(OrderDTO.Status.PARTIAL);
                }
                persistOrder(order, null, null);
            }
            if (before != OrderDTO.Status.FILLED && order.getStatus() == OrderDTO.Status.FILLED) {
                newlyFilled.add(order);
            }
        }
        if (!newlyFilled.isEmpty()) {
            log.info("OES 同步委托：新 FILLED {} 笔（事件/查询）", newlyFilled.size());
        } else {
            log.debug("OES 同步委托, 事件={} 当前委托数={}", events.size(), orders.size());
        }
        return newlyFilled;
    }

    private List<OrderDTO> syncStubFillAll() {
        List<OrderDTO> advanced = new ArrayList<OrderDTO>();
        for (OrderDTO order : orders.values()) {
            if (order == null) {
                continue;
            }
            OrderDTO.Status st = order.getStatus();
            if (st != OrderDTO.Status.SUBMITTED && st != OrderDTO.Status.PARTIAL) {
                continue;
            }
            // OES 跟踪的单在 orderLive 关闭后仍可能残留映射：桩模式也允许推进
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

    private OesOrderService oesOrder() {
        return oesOrderProvider == null ? null : oesOrderProvider.getIfAvailable();
    }

    private static ObjectProvider<OesOrderService> emptyOesProvider() {
        return new ObjectProvider<OesOrderService>() {
            @Override
            public OesOrderService getObject(Object... args) {
                return null;
            }

            @Override
            public OesOrderService getObject() {
                return null;
            }

            @Override
            public OesOrderService getIfAvailable() {
                return null;
            }

            @Override
            public OesOrderService getIfUnique() {
                return null;
            }
        };
    }
}
