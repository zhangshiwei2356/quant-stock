package com.quant.stock.kuangrui;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.task.StrategyTask;
import com.quant.stock.trade.TradeGatewayService;
import com.quant.stock.trade.dto.OrderDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维侧 OES 辅助：只读查询/纸面对账/报撤状态（主工程可编译，不依赖 quant360）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class KuangruiOesOpsFacade {

    private final OesReadonlyService oesReadonlyService;
    private final OesOrderService oesOrderService;
    private final QuantProperties quantProperties;
    private final StrategyTask strategyTask;
    private final TradeGatewayService tradeGatewayService;
    private final KuangruiStaticInfoService staticInfoService;
    private final KuangruiCredentialStore credentialStore;
    private final java.util.concurrent.atomic.AtomicInteger testClSeq =
            new java.util.concurrent.atomic.AtomicInteger((int) (System.currentTimeMillis() % 800_000) + 1000);

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>(oesReadonlyService.status());
        QuantProperties.Kuangrui k = quantProperties.getKuangrui();
        m.put("quantKuangruiEnabled", k != null && k.isEnabled());
        m.put("quantOesEnabled", k != null && k.getOes() != null && k.getOes().isEnabled());
        m.put("staticEnabled", k != null && k.isStaticEnabled());
        m.put("orderEnabled", k != null && k.getOes() != null && k.getOes().isOrderEnabled());
        m.put("orderLive", oesOrderService != null && oesOrderService.isOrderLive());
        if (oesOrderService != null) {
            Map<String, Object> os = oesOrderService.status();
            for (Map.Entry<String, Object> e : os.entrySet()) {
                if (!m.containsKey(e.getKey())) {
                    m.put(e.getKey(), e.getValue());
                }
            }
            m.put("orderImpl", os.get("impl"));
            m.put("orderHint", os.get("hint"));
        }
        m.put("configDir", k == null ? null : k.getConfigDir());
        m.putAll(credentialStore.statusView());
        return m;
    }

    /** 报撤能力状态（M3）。 */
    public Map<String, Object> orderStatus() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (oesOrderService == null) {
            m.put("orderLive", false);
            m.put("hint", "OES 报撤服务未装配");
            return m;
        }
        m.putAll(oesOrderService.status());
        m.put("orderLive", oesOrderService.isOrderLive());
        QuantProperties.Kuangrui k = quantProperties.getKuangrui();
        m.put("tradeMode", quantProperties.getTradeMode());
        m.put("orderEnabled", k != null && k.getOes() != null && k.getOes().isOrderEnabled());
        return m;
    }

    /** M4：证券产品信息。 */
    public Map<String, Object> stock(String code) {
        return queryBlock("stock", new QueryCall() {
            @Override
            public Object call() {
                return oesReadonlyService.queryStock(code);
            }
        });
    }

    /** M4：柜台交易日。 */
    public Map<String, Object> tradingDay() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("live", oesReadonlyService.isLive());
        if (!oesReadonlyService.isLive()) {
            putOesNotLive(m);
            return m;
        }
        try {
            if (!oesReadonlyService.ensureReady()) {
                m.put("ok", false);
                m.putAll(oesReadonlyService.status());
                m.put("message", readyFailMessage(m));
                return m;
            }
            m.putAll(oesReadonlyService.queryTradingDay());
            if (!m.containsKey("ok")) {
                m.put("ok", true);
            }
            return m;
        } catch (Exception e) {
            log.error("[oes-ops] tradingDay 失败: {}", e.getMessage(), e);
            m.put("ok", false);
            m.put("message", e.getMessage());
            return m;
        }
    }

    /** M4：佣金费率。 */
    public Map<String, Object> commissionRate() {
        return queryBlock("commissionRate", new QueryCall() {
            @Override
            public Object call() {
                return oesReadonlyService.queryCommissionRate();
            }
        });
    }

    /** 联调页限价试单（须 orderLive；不改金叉主路径）。 */
    public Map<String, Object> placeTest(String code, String side, BigDecimal priceYuan, Integer qty,
                                         String clientOrderId) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.putAll(orderStatus());
        if (oesOrderService == null || !oesOrderService.isOrderLive()) {
            m.put("ok", false);
            m.put("hint", "报撤未 live。请配置 quant.kuangrui.enabled + oes.enabled + oes.order-enabled=true，"
                    + "并以 mvn -Pkuangrui 启动；order-enabled 仅 application.yml（运行参数页不可热改）");
            return m;
        }
        String norm = OesViewMapper.normalizeCode(code);
        if (norm == null || norm.isEmpty()) {
            m.put("ok", false);
            m.put("message", "code 不能为空");
            return m;
        }
        OrderDTO.Side s = "SELL".equalsIgnoreCase(side) ? OrderDTO.Side.SELL : OrderDTO.Side.BUY;
        if (priceYuan == null || priceYuan.compareTo(BigDecimal.ZERO) <= 0) {
            m.put("ok", false);
            m.put("message", "price 须为正");
            return m;
        }
        int q = qty == null ? 0 : qty.intValue();
        if (q < 100) {
            m.put("ok", false);
            m.put("message", "qty 至少 100（一手）");
            return m;
        }
        int clSeq = testClSeq.incrementAndGet();
        String cid = clientOrderId == null || clientOrderId.trim().isEmpty()
                ? "KR-TEST-" + clSeq : clientOrderId.trim();
        try {
            OesOrderService.OesPlaceResult r = oesOrderService.placeLimit(norm, s, priceYuan, q, clSeq, cid);
            m.put("ok", r != null && r.isAccepted());
            m.put("accepted", r != null && r.isAccepted());
            m.put("clSeqNo", r == null ? clSeq : r.getClSeqNo());
            m.put("clOrdId", r == null ? 0L : r.getClOrdId());
            m.put("message", r == null ? "null result" : r.getMessage());
            m.put("code", norm);
            m.put("side", s.name());
            m.put("price", priceYuan);
            m.put("qty", Integer.valueOf(q));
            m.put("clientOrderId", cid);
            return m;
        } catch (Exception e) {
            log.error("[oes-ops] placeTest 失败: {}", e.getMessage(), e);
            m.put("ok", false);
            m.put("message", e.getMessage());
            return m;
        }
    }

    /** 联调页撤单试单（须 orderLive）。 */
    public Map<String, Object> cancelTest(Integer origClSeqNo, String code) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.putAll(orderStatus());
        if (oesOrderService == null || !oesOrderService.isOrderLive()) {
            m.put("ok", false);
            m.put("hint", "报撤未 live。请配置 quant.kuangrui.enabled + oes.enabled + oes.order-enabled=true，"
                    + "并以 mvn -Pkuangrui 启动");
            return m;
        }
        if (origClSeqNo == null || origClSeqNo.intValue() <= 0) {
            m.put("ok", false);
            m.put("message", "origClSeqNo 无效");
            return m;
        }
        String norm = OesViewMapper.normalizeCode(code);
        try {
            boolean sent = oesOrderService.cancelByClSeqNo(origClSeqNo.intValue(), norm);
            m.put("ok", sent);
            m.put("sent", Boolean.valueOf(sent));
            m.put("origClSeqNo", origClSeqNo);
            m.put("code", norm);
            m.put("message", sent ? "撤单请求已发出（非柜台最终确认）" : "撤单请求失败");
            return m;
        } catch (Exception e) {
            log.error("[oes-ops] cancelTest 失败: {}", e.getMessage(), e);
            m.put("ok", false);
            m.put("message", e.getMessage());
            return m;
        }
    }

    /** M4：静态/费率门面状态。 */
    public Map<String, Object> staticStatus() {
        return staticInfoService.status();
    }

    public Map<String, Object> cash() {
        return queryBlock("cash", new QueryCall() {
            @Override
            public Object call() {
                return oesReadonlyService.queryCash();
            }
        });
    }

    public Map<String, Object> holdings() {
        return queryBlock("holdings", new QueryCall() {
            @Override
            public Object call() {
                return oesReadonlyService.queryHoldings();
            }
        });
    }

    public Map<String, Object> orders() {
        return queryBlock("orders", new QueryCall() {
            @Override
            public Object call() {
                return oesReadonlyService.queryOrders();
            }
        });
    }

    public Map<String, Object> trades() {
        return queryBlock("trades", new QueryCall() {
            @Override
            public Object call() {
                return oesReadonlyService.queryTrades();
            }
        });
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("live", oesReadonlyService.isLive());
        if (!oesReadonlyService.isLive()) {
            putOesNotLive(m);
            m.putAll(oesReadonlyService.snapshot());
            return m;
        }
        try {
            if (!oesReadonlyService.ensureReady()) {
                m.put("ok", false);
                m.putAll(oesReadonlyService.status());
                m.put("message", readyFailMessage(m));
                return m;
            }
            Map<String, Object> snap = oesReadonlyService.snapshot();
            m.putAll(snap);
            m.put("ok", Boolean.TRUE.equals(snap.get("ok")) || snap.get("cash") != null);
            if (Boolean.TRUE.equals(m.get("syncDegraded"))) {
                m.put("hint", "回报未同步(syncDegraded)，查询结果仍可用；报撤需 rptSynced=true");
            }
            return m;
        } catch (Exception e) {
            log.error("[oes-ops] snapshot 失败: {}", e.getMessage(), e);
            m.put("ok", false);
            m.put("message", e.getMessage());
            return m;
        }
    }

    /**
     * 纸面本地账本 vs 柜台只读快照差异（不改仓、不推进委托）。
     */
    public Map<String, Object> reconcile() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("live", oesReadonlyService.isLive());
        Map<String, Object> local = localSnapshot();
        m.put("local", local);
        if (!oesReadonlyService.isLive()) {
            m.put("ok", false);
            m.put("message", "OES 未启用；仅返回本地快照");
            m.put("broker", oesReadonlyService.snapshot());
            m.put("gaps", new ArrayList<Map<String, Object>>());
            return m;
        }
        try {
            if (!oesReadonlyService.ensureReady()) {
                Map<String, Object> st = oesReadonlyService.status();
                m.put("ok", false);
                m.put("message", readyFailMessage(st));
                m.put("broker", st);
                m.put("gaps", new ArrayList<Map<String, Object>>());
                return m;
            }
            Map<String, Object> broker = oesReadonlyService.snapshot();
            m.put("broker", broker);
            List<Map<String, Object>> gaps = buildGaps(local, broker);
            m.put("gaps", gaps);
            m.put("ok", true);
            m.put("gapCount", gaps.size());
            return m;
        } catch (Exception e) {
            log.error("[oes-ops] reconcile 失败: {}", e.getMessage(), e);
            m.put("ok", false);
            m.put("message", e.getMessage());
            m.put("gaps", new ArrayList<Map<String, Object>>());
            return m;
        }
    }

    public Map<String, Object> stop() {
        oesReadonlyService.stop();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.putAll(oesReadonlyService.status());
        return m;
    }

    /**
     * 定时任务旁路：OES live 时拉柜台快照并打对账日志（不改本地账本）。
     */
    public void logReconcileIfLive(String jobCode) {
        if (!oesReadonlyService.isLive()) {
            return;
        }
        try {
            Map<String, Object> r = reconcile();
            Object gapCount = r.get("gapCount");
            log.info("[oes-reconcile] job={} ok={} gaps={} cashRows={} holdRows={} ordRows={} trdRows={}",
                    jobCode,
                    r.get("ok"),
                    gapCount,
                    sizeOf(r, "broker", "cash"),
                    sizeOf(r, "broker", "holdings"),
                    sizeOf(r, "broker", "orders"),
                    sizeOf(r, "broker", "trades"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> gaps = (List<Map<String, Object>>) r.get("gaps");
            if (gaps != null) {
                int n = 0;
                for (Map<String, Object> g : gaps) {
                    if (n++ >= 20) {
                        log.info("[oes-reconcile] …其余 gap 省略");
                        break;
                    }
                    log.info("[oes-reconcile] gap dim={} code={} title={} detail={}",
                            g.get("dimension"), g.get("code"), g.get("title"), g.get("detail"));
                }
            }
        } catch (Exception e) {
            log.error("[oes-reconcile] job={} 失败: {}", jobCode, e.getMessage(), e);
        }
    }

    private Map<String, Object> localSnapshot() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("cash", strategyTask.getSimCash());
        m.put("equity", strategyTask.getMarkEquity());
        List<Map<String, Object>> holds = strategyTask.listLivePositionViews();
        List<Map<String, Object>> holdViews = new ArrayList<Map<String, Object>>();
        if (holds != null) {
            for (Map<String, Object> row : holds) {
                Map<String, Object> h = new LinkedHashMap<String, Object>();
                h.put("code", String.valueOf(row.get("code")));
                Object vol = row.get("volume");
                h.put("sumHld", vol instanceof Number ? ((Number) vol).longValue() : 0L);
                h.put("costPrice", row.get("avgCost"));
                holdViews.add(h);
            }
        }
        m.put("holdings", holdViews);
        List<Map<String, Object>> ords = new ArrayList<Map<String, Object>>();
        for (OrderDTO o : tradeGatewayService.listOrders()) {
            if (o == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("orderId", o.getOrderId());
            row.put("clientOrderId", o.getClientOrderId());
            row.put("code", o.getStockCode());
            row.put("side", o.getSide() == null ? null : o.getSide().name());
            row.put("status", o.getStatus() == null ? null : o.getStatus().name());
            row.put("volume", o.getVolume());
            row.put("filledVolume", o.getFilledVolume());
            row.put("price", o.getPrice());
            ords.add(row);
        }
        m.put("orders", ords);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildGaps(Map<String, Object> local, Map<String, Object> broker) {
        List<Map<String, Object>> gaps = new ArrayList<Map<String, Object>>();
        Map<String, Long> localQty = new HashMap<String, Long>();
        List<Map<String, Object>> lh = (List<Map<String, Object>>) local.get("holdings");
        if (lh != null) {
            for (Map<String, Object> h : lh) {
                String code = OesViewMapper.normalizeCode(String.valueOf(h.get("code")));
                Object q = h.get("sumHld");
                long qty = q instanceof Number ? ((Number) q).longValue() : 0L;
                localQty.put(code, qty);
            }
        }
        Map<String, Long> brokerQty = new HashMap<String, Long>();
        List<Map<String, Object>> bh = (List<Map<String, Object>>) broker.get("holdings");
        if (bh != null) {
            for (Map<String, Object> h : bh) {
                String code = OesViewMapper.normalizeCode(String.valueOf(h.get("code")));
                Object q = h.get("sumHld");
                long qty = q instanceof Number ? ((Number) q).longValue() : 0L;
                brokerQty.put(code, Long.valueOf(qty));
            }
        }
        for (Map.Entry<String, Long> e : localQty.entrySet()) {
            Long b = brokerQty.get(e.getKey());
            long bv = b == null ? 0L : b.longValue();
            if (bv != e.getValue().longValue()) {
                gaps.add(gap("HOLDING", e.getKey(), "持仓数量不一致",
                        "local=" + e.getValue() + " broker=" + bv));
            }
        }
        for (Map.Entry<String, Long> e : brokerQty.entrySet()) {
            if (!localQty.containsKey(e.getKey()) && e.getValue().longValue() != 0L) {
                gaps.add(gap("HOLDING", e.getKey(), "柜台有仓本地无",
                        "broker=" + e.getValue()));
            }
        }
        Object localCashObj = local.get("cash");
        BigDecimal localCash = localCashObj instanceof BigDecimal
                ? (BigDecimal) localCashObj
                : (localCashObj == null ? BigDecimal.ZERO : new BigDecimal(localCashObj.toString()));
        BigDecimal brokerAvail = firstCashAvailable(broker);
        if (brokerAvail != null) {
            BigDecimal diff = localCash.subtract(brokerAvail).abs();
            if (diff.compareTo(new BigDecimal("0.01")) >= 0) {
                gaps.add(gap("CASH", "", "现金不一致",
                        "local=" + localCash.toPlainString()
                                + " brokerAvailable=" + brokerAvail.toPlainString()));
            }
        }
        return gaps;
    }

    @SuppressWarnings("unchecked")
    private static BigDecimal firstCashAvailable(Map<String, Object> broker) {
        List<Map<String, Object>> cash = (List<Map<String, Object>>) broker.get("cash");
        if (cash == null || cash.isEmpty()) {
            return null;
        }
        Object v = cash.get(0).get("currentAvailableBal");
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(v.toString());
    }

    private static Map<String, Object> gap(String dim, String code, String title, String detail) {
        Map<String, Object> g = new LinkedHashMap<String, Object>();
        g.put("dimension", dim);
        g.put("code", code);
        g.put("title", title);
        g.put("detail", detail);
        return g;
    }

    @SuppressWarnings("unchecked")
    private static int sizeOf(Map<String, Object> root, String nested, String listKey) {
        Object n = root.get(nested);
        if (!(n instanceof Map)) {
            return 0;
        }
        Object list = ((Map<String, Object>) n).get(listKey);
        if (list instanceof List) {
            return ((List<?>) list).size();
        }
        return 0;
    }

    /** 未 live 时附带 status.hint，联调页可直接看到启用步骤。 */
    private void putOesNotLive(Map<String, Object> m) {
        m.put("ok", false);
        m.put("live", false);
        m.put("message", "OES 未启用或未编译进 classpath");
        Map<String, Object> st = oesReadonlyService.status();
        if (st != null) {
            if (st.get("hint") != null) {
                m.put("hint", st.get("hint"));
            }
            if (st.get("impl") != null) {
                m.put("impl", st.get("impl"));
            }
        }
    }

    private Map<String, Object> queryBlock(String key, QueryCall call) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("live", oesReadonlyService.isLive());
        if (!oesReadonlyService.isLive()) {
            putOesNotLive(m);
            m.put(key, new ArrayList<Object>());
            return m;
        }
        try {
            if (!oesReadonlyService.ensureReady()) {
                m.put("ok", false);
                m.put(key, new ArrayList<Object>());
                m.putAll(oesReadonlyService.status());
                m.put("message", readyFailMessage(m));
                return m;
            }
            Object data = call.call();
            m.put("ok", true);
            m.put(key, data);
            if (data instanceof List) {
                m.put("count", ((List<?>) data).size());
            }
            Map<String, Object> st = oesReadonlyService.status();
            // 查询 0 条时把 lastError（若有签名/异常明细）带给联调页
            if (data instanceof List && ((List<?>) data).isEmpty() && st.get("lastError") != null) {
                m.put("lastError", st.get("lastError"));
                m.put("hint", "查询返回 0 条；若 lastError 含签名/异常请对照资料包 Demo");
            }
            if (Boolean.TRUE.equals(st.get("syncDegraded"))) {
                m.put("syncDegraded", true);
                m.put("rptSynced", false);
                m.put("hint", "回报未同步(syncDegraded)，查询通道结果仍可用；报撤需 rptSynced=true");
                if (st.get("lastError") != null) {
                    m.put("lastError", st.get("lastError"));
                }
            }
            return m;
        } catch (Exception e) {
            log.error("[oes-ops] {} 失败: {}", key, e.getMessage(), e);
            m.put("ok", false);
            m.put("message", e.getMessage());
            m.put(key, new ArrayList<Object>());
            m.putAll(oesReadonlyService.status());
            return m;
        }
    }

    /** 把 lastError 拼进失败 message，便于联调页一眼看到根因。 */
    private static String readyFailMessage(Map<String, Object> status) {
        Object err = status == null ? null : status.get("lastError");
        if (err != null && String.valueOf(err).trim().length() > 0) {
            return "OES 未就绪: " + err;
        }
        return "OES 登录失败（无 lastError；请确认 -Pkuangrui 重编后启动，出参应含 rptSyncEngine=OesRptSyncInvoker/v2）";
    }

    private interface QueryCall {
        Object call() throws Exception;
    }
}
