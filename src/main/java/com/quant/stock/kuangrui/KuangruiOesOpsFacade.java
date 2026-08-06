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
 * 运维侧 OES 只读辅助：状态/查询/纸面对账（主工程可编译，不依赖 quant360）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class KuangruiOesOpsFacade {

    private final OesReadonlyService oesReadonlyService;
    private final QuantProperties quantProperties;
    private final StrategyTask strategyTask;
    private final TradeGatewayService tradeGatewayService;

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>(oesReadonlyService.status());
        QuantProperties.Kuangrui k = quantProperties.getKuangrui();
        m.put("quantKuangruiEnabled", k != null && k.isEnabled());
        m.put("quantOesEnabled", k != null && k.getOes() != null && k.getOes().isEnabled());
        m.put("orderEnabled", k != null && k.getOes() != null && k.getOes().isOrderEnabled());
        m.put("configDir", k == null ? null : k.getConfigDir());
        return m;
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
            m.put("ok", false);
            m.put("message", "OES 未启用或未编译进 classpath（见 status.hint）");
            m.putAll(oesReadonlyService.snapshot());
            return m;
        }
        try {
            if (!oesReadonlyService.ensureReady()) {
                m.put("ok", false);
                m.put("message", "OES 登录/回报同步失败");
                m.putAll(oesReadonlyService.status());
                return m;
            }
            Map<String, Object> snap = oesReadonlyService.snapshot();
            m.putAll(snap);
            m.put("ok", Boolean.TRUE.equals(snap.get("ok")) || snap.get("cash") != null);
            return m;
        } catch (Exception e) {
            log.warn("[oes-ops] snapshot 失败: {}", e.getMessage());
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
                m.put("ok", false);
                m.put("message", "OES 登录/回报同步失败");
                m.put("broker", oesReadonlyService.status());
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
            log.warn("[oes-ops] reconcile 失败: {}", e.getMessage());
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
            log.warn("[oes-reconcile] job={} 失败: {}", jobCode, e.getMessage());
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

    private Map<String, Object> queryBlock(String key, QueryCall call) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("live", oesReadonlyService.isLive());
        if (!oesReadonlyService.isLive()) {
            m.put("ok", false);
            m.put("message", "OES 未启用或未编译进 classpath（见 status.hint）");
            m.put(key, new ArrayList<Object>());
            return m;
        }
        try {
            if (!oesReadonlyService.ensureReady()) {
                m.put("ok", false);
                m.put("message", "OES 登录/回报同步失败");
                m.put(key, new ArrayList<Object>());
                m.putAll(oesReadonlyService.status());
                return m;
            }
            Object data = call.call();
            m.put("ok", true);
            m.put(key, data);
            if (data instanceof List) {
                m.put("count", ((List<?>) data).size());
            }
            return m;
        } catch (Exception e) {
            log.warn("[oes-ops] {} 失败: {}", key, e.getMessage());
            m.put("ok", false);
            m.put("message", e.getMessage());
            m.put(key, new ArrayList<Object>());
            return m;
        }
    }

    private interface QueryCall {
        Object call() throws Exception;
    }
}
