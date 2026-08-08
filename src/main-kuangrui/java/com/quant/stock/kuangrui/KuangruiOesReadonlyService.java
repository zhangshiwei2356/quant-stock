package com.quant.stock.kuangrui;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.trade.dto.OrderDTO;
import com.quant360.api.callback.OesCallBack;
import com.quant360.api.client.impl.OesClientImpl;
import com.quant360.api.model.ClientLogonReq;
import com.quant360.api.model.ClientLogonRsp;
import com.quant360.api.model.oes.enu.OesBusinessType;
import com.quant360.api.model.oes.enu.OesLogonEncryptType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 宽睿 OES：登录 + {@code sendRptSync} + 只读查询（M2）；可选报撤与回报队列（M3，{@code oes.order-enabled}）。
 * 仅 {@code -Pkuangrui} 编译；API 调用经反射适配资料包签名差异。
 */
@Slf4j
@Service
@Primary
@ConditionalOnClass(OesClientImpl.class)
@ConditionalOnProperty(name = {
        "quant.kuangrui.enabled",
        "quant.kuangrui.oes.enabled"
}, havingValue = "true")
public class KuangruiOesReadonlyService implements OesReadonlyService, OesOrderService {

    private static final int OES_ORD_TYPE_LMT = 0;
    private static final int OES_BS_BUY = 1;
    private static final int OES_BS_SELL = 2;

    private final QuantProperties quantProperties;
    private final org.springframework.beans.factory.ObjectProvider<KuangruiCredentialStore> credentialStoreProvider;

    private final Object clientLock = new Object();
    private final AtomicBoolean rptSynced = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<String>();
    private final AtomicReference<String> applVerId = new AtomicReference<String>();
    private final ConcurrentLinkedQueue<OesOrderEvent> eventQueue = new ConcurrentLinkedQueue<OesOrderEvent>();
    private volatile OesClientImpl client;
    private volatile long lastInMsgSeq;

    public KuangruiOesReadonlyService(QuantProperties quantProperties,
                                      org.springframework.beans.factory.ObjectProvider<KuangruiCredentialStore> credentialStoreProvider) {
        this.quantProperties = quantProperties;
        this.credentialStoreProvider = credentialStoreProvider;
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public boolean isOrderLive() {
        return isLive() && quantProperties.getKuangrui() != null
                && quantProperties.getKuangrui().getOes() != null
                && quantProperties.getKuangrui().getOes().isOrderEnabled();
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("live", true);
        m.put("orderLive", isOrderLive());
        m.put("impl", "kuangrui-oes");
        m.put("loggedIn", client != null);
        m.put("rptSynced", rptSynced.get());
        m.put("syncDegraded", client != null && !rptSynced.get());
        m.put("rptSyncEngine", "OesRptSyncInvoker/v2");
        m.put("lastInMsgSeq", lastInMsgSeq);
        m.put("applVerId", applVerId.get());
        m.put("lastError", lastError.get());
        m.put("pendingEvents", eventQueue.size());
        m.put("configPath", resolveOesConfig().toString());
        m.put("configExists", Files.isRegularFile(resolveOesConfig()));
        KuangruiCredentials cr = resolveCred();
        m.put("hasCred", cr.isPresent());
        m.put("credSource", cr.getSource());
        if (cr.isPresent()) {
            m.put("activeUsername", cr.getUsername());
        }
        m.put("orderEnabled", quantProperties.getKuangrui() != null
                && quantProperties.getKuangrui().getOes() != null
                && quantProperties.getKuangrui().getOes().isOrderEnabled());
        String hint;
        if (client != null && !rptSynced.get()) {
            hint = "已登录但回报未同步（syncDegraded）：查资金/持仓等查询通道仍可用；报撤需 rptSynced=true。"
                    + " 若 lastError 仍是旧文案「请核对 API 版本 0.19.4」，说明未用最新代码重编，请 git pull 后 mvn -Pkuangrui 重新编译启动。";
        } else if (isOrderLive()) {
            hint = "M3 报撤已开：限价 sendOrdReq/撤单 + 回报/查询推进；M4 产品/交易日/佣金可查";
        } else {
            hint = "M2 只读 + M4 查询（stock/tradingDay/commission）；报撤需 oes.order-enabled=true（M3）";
        }
        m.put("hint", hint);
        return m;
    }

    @Override
    public boolean ensureReady() {
        try {
            ensureClient();
            // 查询通道：登录成功即可；回报同步失败时降级仍允许查资金等
            return client != null;
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.warn("[oes] 就绪失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<Map<String, Object>> queryCash() {
        ensureReadyOrThrow();
        List<?> raw = invokeOesQuery(
                new String[]{"queryCashAsset", "queryCashAssets", "queryCash"},
                new String[]{
                        "com.quant360.api.model.oes.OesQryCashAssetFilter",
                        "com.quant360.api.model.oes.qry.OesQryCashAssetFilter",
                        "com.quant360.api.model.OesQryCashAssetFilter"
                });
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : raw) {
            out.add(OesViewMapper.cash(
                    str(firstGetter(item, "getCashAcctId", "getCashAcctID", "getAccountId")),
                    lng(firstGetter(item, "getCurrentTotalBal", "getCurrentBal", "getTotalBal")),
                    lng(firstGetter(item, "getCurrentAvailableBal", "getAvailableBal", "getAvailBal")),
                    lng(firstGetter(item, "getCurrentDrawableBal", "getDrawableBal", "getDrawBal"))
            ));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> queryHoldings() {
        ensureReadyOrThrow();
        List<?> raw = invokeOesQuery(
                new String[]{"queryStkHolding", "queryStockHolding", "queryHolding", "queryStkHoldings"},
                new String[]{
                        "com.quant360.api.model.oes.OesQryStkHoldingFilter",
                        "com.quant360.api.model.oes.qry.OesQryStkHoldingFilter",
                        "com.quant360.api.model.OesQryStkHoldingFilter"
                });
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : raw) {
            String code = str(firstGetter(item, "getSecurityId", "getSecurityID", "getInstrId"));
            out.add(OesViewMapper.holding(
                    code,
                    lng(firstGetter(item, "getSumHld", "getTotalHld", "getHldQty")),
                    lng(firstGetter(item, "getSellAvlHld", "getSellAvailableHld", "getAvailableHld")),
                    lng(firstGetter(item, "getCostPrice", "getAvgCostPrice", "getCostPx"))
            ));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> queryOrders() {
        ensureReadyOrThrow();
        List<?> raw = invokeOesQuery(
                new String[]{"queryOrder", "queryOrders", "queryOrd"},
                new String[]{
                        "com.quant360.api.model.oes.OesQryOrdFilter",
                        "com.quant360.api.model.oes.qry.OesQryOrdFilter",
                        "com.quant360.api.model.oes.OesQryOrderFilter",
                        "com.quant360.api.model.OesQryOrdFilter"
                });
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : raw) {
            String code = str(firstGetter(item, "getSecurityId", "getSecurityID"));
            Object st = firstGetter(item, "getOrdStatus", "getOrderStatus", "getStatus");
            int status = toStatusInt(st);
            out.add(OesViewMapper.order(
                    code,
                    lng(firstGetter(item, "getClOrdId", "getClOrdID")),
                    (int) lng(firstGetter(item, "getClSeqNo", "getClSeqNO")),
                    status,
                    lng(firstGetter(item, "getOrdPrice", "getOrderPrice", "getPrice")),
                    (int) lng(firstGetter(item, "getOrdQty", "getOrderQty", "getQty")),
                    (int) lng(firstGetter(item, "getCumQty", "getFilledQty"))
            ));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> queryTrades() {
        ensureReadyOrThrow();
        List<?> raw = invokeOesQuery(
                new String[]{"queryTrade", "queryTrades", "queryTrd"},
                new String[]{
                        "com.quant360.api.model.oes.OesQryTrdFilter",
                        "com.quant360.api.model.oes.qry.OesQryTrdFilter",
                        "com.quant360.api.model.oes.OesQryTradeFilter",
                        "com.quant360.api.model.OesQryTrdFilter"
                });
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : raw) {
            String code = str(firstGetter(item, "getSecurityId", "getSecurityID"));
            out.add(OesViewMapper.trade(
                    code,
                    lng(firstGetter(item, "getClOrdId", "getClOrdID")),
                    lng(firstGetter(item, "getTrdPrice", "getTradePrice", "getPrice")),
                    (int) lng(firstGetter(item, "getTrdQty", "getTradeQty", "getQty")),
                    lng(firstGetter(item, "getTrdAmt", "getTradeAmt", "getAmount"))
            ));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> queryStock(String code) {
        ensureReadyOrThrow();
        Object filter = newInstance("com.quant360.api.model.oes.OesQryStockFilter");
        String norm = OesViewMapper.normalizeCode(code);
        if (filter != null && norm != null && !norm.isEmpty()) {
            setBean(filter, "setSecurityId", norm);
            setBean(filter, "setSecurityID", norm);
            int mkt = KuangruiExchangeIds.fromStockCode(norm);
            if (mkt > 0) {
                setBean(filter, "setMktId", Integer.valueOf(mkt));
            }
        }
        List<?> raw = invokeQueryListWithFilter("queryStock", filter);
        if (raw.isEmpty()) {
            raw = invokeQueryListWithFilter("queryStockItem", filter);
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : raw) {
            out.add(mapStockItem(item));
        }
        return out;
    }

    @Override
    public Map<String, Object> queryTradingDay() {
        ensureReadyOrThrow();
        Object ret = invokeReturning(client, "queryTradingDay");
        if (ret == null) {
            ret = invokeReturning(client, "queryTradingDay", new Object[]{null});
        }
        int day = 0;
        if (ret instanceof Number) {
            day = ((Number) ret).intValue();
        } else if (ret instanceof List && !((List<?>) ret).isEmpty()) {
            day = (int) lng(firstGetter(((List<?>) ret).get(0),
                    "getTradingDay", "getTrdDay", "getTradeDate"));
        } else if (ret != null) {
            day = (int) lng(firstGetter(ret, "getTradingDay", "getTrdDay", "getTradeDate"));
            if (day == 0) {
                day = (int) lng(ret);
            }
        }
        if (day <= 0) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("tradingDayRaw", 0);
            empty.put("tradingDay", "");
            empty.put("ok", false);
            return empty;
        }
        Map<String, Object> m = OesViewMapper.tradingDay(day);
        m.put("ok", true);
        return m;
    }

    @Override
    public List<Map<String, Object>> queryCommissionRate() {
        ensureReadyOrThrow();
        Object filter = newInstance("com.quant360.api.model.oes.OesQryCommissionRateFilter");
        List<?> raw = invokeQueryListWithFilter("queryCommissionRate", filter);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : raw) {
            Object fr = firstGetter(item, "getFeeRate", "getCommissionRate", "getRate");
            long feeRaw = lng(fr);
            BigDecimal asDecimal = null;
            if (fr instanceof BigDecimal) {
                asDecimal = (BigDecimal) fr;
            } else if (fr instanceof Double || fr instanceof Float) {
                asDecimal = BigDecimal.valueOf(((Number) fr).doubleValue());
            }
            out.add(OesViewMapper.commission(
                    (int) lng(firstGetter(item, "getFeeType", "getFeeTypeId")),
                    (int) lng(firstGetter(item, "getBsType", "getBsTypeId")),
                    feeRaw,
                    lng(firstGetter(item, "getMinFee", "getMinCommission")),
                    asDecimal != null && asDecimal.compareTo(BigDecimal.ONE) < 0 ? asDecimal : null
            ));
        }
        return out;
    }

    private Map<String, Object> mapStockItem(Object item) {
        String code = str(firstGetter(item, "getSecurityId", "getSecurityID"));
        return OesViewMapper.stock(
                code,
                str(firstGetter(item, "getSecurityName", "getSecurityNameUTF8", "getName")),
                lng(firstGetter(item, "getUpperLimitPrice", "getPriceLimitUpper", "getCeilPrice")),
                lng(firstGetter(item, "getLowerLimitPrice", "getPriceLimitLower", "getFloorPrice")),
                lng(firstGetter(item, "getPrevClose", "getPreClosePrice", "getPrevClosePrice")),
                lng(firstGetter(item, "getOutstandingShare", "getTotalShare", "getEquity")),
                lng(firstGetter(item, "getPublicFloatShare", "getFloatShare", "getCirculationShare")),
                (int) lng(firstGetter(item, "getSuspFlag", "getSuspendFlag", "getIsSuspend")),
                (int) lng(firstGetter(item, "getSecurityStatus", "getSecurityStatusFlag", "getProductStatus"))
        );
    }

    private List<?> invokeQueryListWithFilter(String methodName, Object filter) {
        OesClientImpl c = client;
        if (c == null) {
            return Collections.emptyList();
        }
        List<Object> filters = new ArrayList<Object>();
        if (filter != null) {
            filters.add(filter);
        }
        OesQueryListInvoker.Result r = OesQueryListInvoker.invokeWithFilters(
                c, new String[]{methodName}, filters);
        return finishQueryResult(methodName, r);
    }

    /** 增强查询：多方法名 / Filter 类 / 回调收集；失败写入 lastError 并 WARN。 */
    private List<?> invokeOesQuery(String[] methodNames, String[] filterClasses) {
        OesClientImpl c = client;
        if (c == null) {
            return Collections.emptyList();
        }
        OesQueryListInvoker.Result r = OesQueryListInvoker.invoke(c, methodNames, filterClasses);
        return finishQueryResult(methodNames[0], r);
    }

    private List<?> finishQueryResult(String methodName, OesQueryListInvoker.Result r) {
        if (!r.ok) {
            lastError.set(r.detail);
            // 签名探测失败常含 NPE 文案：升级 ERROR，便于 IDEA 按 error 过滤看到
            if (r.detail != null && (r.detail.contains("NullPointerException")
                    || r.detail.contains("mode is null")
                    || r.detail.contains("IllegalArgumentException"))) {
                log.error("[oes] {} 查询失败（含空参/非法入参迹象）: {}", methodName, r.detail);
            } else {
                log.warn("[oes] {} 查询失败: {}", methodName, r.detail);
            }
            return Collections.emptyList();
        }
        if (r.list.isEmpty()) {
            log.info("[oes] {} 返回 0 条 via {}", methodName, r.methodUsed);
        } else {
            log.info("[oes] {} 返回 {} 条 via {}", methodName, r.list.size(), r.methodUsed);
        }
        return r.list;
    }

    private static Object firstGetter(Object target, String... getters) {
        if (target == null || getters == null) {
            return null;
        }
        for (String g : getters) {
            Object v = invokeGetter(target, g);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("live", true);
        try {
            ensureReadyOrThrow();
            List<Map<String, Object>> cash = queryCash();
            List<Map<String, Object>> holdings = queryHoldings();
            List<Map<String, Object>> orders = queryOrders();
            List<Map<String, Object>> trades = queryTrades();
            m.put("ok", true);
            m.put("cash", cash);
            m.put("holdings", holdings);
            m.put("orders", orders);
            m.put("trades", trades);
            m.put("cashCount", cash.size());
            m.put("holdingCount", holdings.size());
            m.put("orderCount", orders.size());
            m.put("tradeCount", trades.size());
            lastError.set(null);
        } catch (Exception e) {
            lastError.set(e.getMessage());
            m.put("ok", false);
            m.put("message", e.getMessage());
            m.put("cash", Collections.emptyList());
            m.put("holdings", Collections.emptyList());
            m.put("orders", Collections.emptyList());
            m.put("trades", Collections.emptyList());
        }
        m.putAll(status());
        return m;
    }

    @Override
    public OesPlaceResult placeLimit(String stockCode, OrderDTO.Side side, BigDecimal priceYuan, int qty,
                                     int clSeqNo, String clientOrderId) {
        if (!isOrderLive()) {
            return OesPlaceResult.fail(clSeqNo, "oes.order-enabled=false");
        }
        try {
            ensureReadyOrThrow();
            if (!rptSynced.get()) {
                return OesPlaceResult.fail(clSeqNo, "回报未同步(rptSynced=false)，禁止报撤；可先查资金。详情: "
                        + lastError.get());
            }
            String code = OesViewMapper.normalizeCode(stockCode);
            int mkt = KuangruiExchangeIds.fromStockCode(code);
            if (mkt == 0 || qty < 100 || priceYuan == null) {
                return OesPlaceResult.fail(clSeqNo, "非法标的/数量/价格");
            }
            int pxMilli = KuangruiPriceScale.toMilliInt(priceYuan);
            if (pxMilli <= 0) {
                return OesPlaceResult.fail(clSeqNo, "价格毫级无效");
            }
            Object req = newInstance("com.quant360.api.model.oes.OesOrdReq");
            if (req == null) {
                return OesPlaceResult.fail(clSeqNo, "无法创建 OesOrdReq");
            }
            setBean(req, "setClSeqNo", Integer.valueOf(clSeqNo));
            setBean(req, "setMktId", Integer.valueOf(mkt));
            setBean(req, "setOrdType", Integer.valueOf(OES_ORD_TYPE_LMT));
            setBean(req, "setBsType", Integer.valueOf(side == OrderDTO.Side.SELL ? OES_BS_SELL : OES_BS_BUY));
            setBean(req, "setSecurityId", code);
            setBean(req, "setOrdQty", Integer.valueOf(qty));
            setBean(req, "setOrdPrice", Integer.valueOf(pxMilli));
            Object ret = invokeReturning(client, "sendOrdReq", req);
            if (ret == null) {
                ret = invokeReturning(client, "sendOrderReq", req);
            }
            if (ret instanceof Number && ((Number) ret).intValue() < 0) {
                return OesPlaceResult.fail(clSeqNo, "sendOrdReq 返回 " + ret);
            }
            long clOrdId = lng(invokeGetter(ret, "getClOrdId"));
            if (clOrdId == 0L && ret instanceof Number) {
                // 部分版本返回 int 错误码 0=成功
                clOrdId = 0L;
            }
            log.info("[oes] 报单已发 clSeqNo={} {} {}@{} x{} clientId={}",
                    clSeqNo, side, code, priceYuan, qty, clientOrderId);
            lastError.set(null);
            return OesPlaceResult.ok(clSeqNo, clOrdId);
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.warn("[oes] 报单失败 clSeqNo={}: {}", clSeqNo, e.getMessage());
            return OesPlaceResult.fail(clSeqNo, e.getMessage());
        }
    }

    @Override
    public boolean cancelByClSeqNo(int origClSeqNo, String stockCode) {
        if (!isOrderLive()) {
            return false;
        }
        try {
            ensureReadyOrThrow();
            if (!rptSynced.get()) {
                log.warn("[oes] 回报未同步，拒绝撤单 lastError={}", lastError.get());
                return false;
            }
            String code = OesViewMapper.normalizeCode(stockCode);
            int mkt = KuangruiExchangeIds.fromStockCode(code);
            Object req = newInstance("com.quant360.api.model.oes.OesOrdCancelReq");
            if (req == null) {
                // 部分版本撤单也用 OesOrdReq + origClSeqNo
                req = newInstance("com.quant360.api.model.oes.OesOrdReq");
            }
            if (req == null) {
                throw new IllegalStateException("无法创建撤单请求对象");
            }
            setBean(req, "setClSeqNo", Integer.valueOf(nextInternalCancelSeq(origClSeqNo)));
            setBean(req, "setOrigClSeqNo", Integer.valueOf(origClSeqNo));
            if (mkt > 0) {
                setBean(req, "setMktId", Integer.valueOf(mkt));
            }
            if (code != null && !code.isEmpty()) {
                setBean(req, "setSecurityId", code);
            }
            Object ret = invokeReturning(client, "sendOrdCancelReq", req);
            if (ret == null) {
                ret = invokeReturning(client, "sendOrderCancelReq", req);
            }
            if (ret instanceof Number && ((Number) ret).intValue() < 0) {
                log.warn("[oes] 撤单返回码 {}", ret);
                return false;
            }
            log.info("[oes] 撤单已发 origClSeqNo={} code={}", origClSeqNo, code);
            return true;
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.warn("[oes] 撤单失败 origClSeqNo={}: {}", origClSeqNo, e.getMessage());
            return false;
        }
    }

    @Override
    public List<OesOrderEvent> pollEvents() {
        List<OesOrderEvent> out = new ArrayList<OesOrderEvent>();
        OesOrderEvent e;
        while ((e = eventQueue.poll()) != null) {
            out.add(e);
        }
        // 查询通道补强：把柜台委托现状也转成事件，避免回调签名不适配时无法推进
        if (isOrderLive() && client != null && rptSynced.get()) {
            try {
                for (Map<String, Object> row : queryOrders()) {
                    Object seqObj = row.get("clSeqNo");
                    Object stObj = row.get("ordStatus");
                    Object cumObj = row.get("cumQty");
                    Object clOrdObj = row.get("clOrdId");
                    if (!(seqObj instanceof Number) || !(stObj instanceof Number)) {
                        continue;
                    }
                    out.add(new OesOrderEvent(
                            OesOrderEvent.Kind.ORDER,
                            ((Number) seqObj).intValue(),
                            clOrdObj instanceof Number ? ((Number) clOrdObj).longValue() : 0L,
                            String.valueOf(row.get("code")),
                            ((Number) stObj).intValue(),
                            cumObj instanceof Number ? ((Number) cumObj).intValue() : 0,
                            0,
                            null
                    ));
                }
            } catch (Exception ex) {
                log.debug("[oes] poll 查询补强失败: {}", ex.getMessage());
            }
        }
        return out;
    }

    /** 撤单自身也需要新的 clSeqNo；用 orig + 大偏移避免与主流水冲突。 */
    private static int nextInternalCancelSeq(int origClSeqNo) {
        long v = 800_000_000L + (origClSeqNo & 0x7fffffff);
        if (v > Integer.MAX_VALUE) {
            v = Integer.MAX_VALUE - (origClSeqNo & 0xffff);
        }
        return (int) v;
    }

    @Override
    public void stop() {
        synchronized (clientLock) {
            rptSynced.set(false);
            eventQueue.clear();
            closeClient();
        }
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    private void ensureReadyOrThrow() {
        if (!ensureReady()) {
            throw new IllegalStateException("OES 未就绪: " + lastError.get());
        }
    }

    private void ensureClient() throws Exception {
        synchronized (clientLock) {
            if (client != null && rptSynced.get()) {
                return;
            }
            // 已登录：再尝试一次 sync；失败则保持查询通道（降级）
            if (client != null) {
                if (!rptSynced.get()) {
                    try {
                        doRptSync(lastInMsgSeq);
                    } catch (Exception e) {
                        lastError.set(e.getMessage());
                        log.warn("[oes] 再次回报同步失败，保持查询降级: {}", e.getMessage());
                    }
                }
                return;
            }
            Path cfg = resolveOesConfig();
            if (!Files.isRegularFile(cfg)) {
                throw new IllegalStateException("缺少 OES 配置: " + cfg.toAbsolutePath());
            }
            KuangruiCredentials cred = resolveCred();
            if (!cred.isPresent()) {
                throw new IllegalStateException(
                        "无宽睿账号：请在「宽睿联调 → 账号登录」验柜入库，或设置 QUANT_KUANGRUI_USER / PASSWORD");
            }
            String user = cred.getUsername();
            String pass = cred.getPassword();
            String driver = envOr("QUANT_KUANGRUI_DRIVER_ID", "DAEB7F56");
            OesClientImpl c = new OesClientImpl(1, cfg.toAbsolutePath().toString());
            c.initCallBack(buildCallback());
            ClientLogonReq logon = new ClientLogonReq();
            logon.setHeartBtInt(30);
            logon.setUsername(user);
            logon.setPassword(pass);
            logon.setClientDriverId(driver);
            logon.setBusinessType(OesBusinessType.OES_BUSINESS_TYPE_STOCK);
            Integer enc = resolveEncryptType();
            if (enc != null) {
                logon.setLogonEncryptType(OesLogonEncryptType.valueOf(enc.intValue()));
            }
            String ip = env("QUANT_KUANGRUI_CLIENT_IP");
            String mac = env("QUANT_KUANGRUI_CLIENT_MAC");
            if (ip != null) {
                logon.setClientIp(ip);
            }
            if (mac != null) {
                logon.setClientMac(mac.replace('-', ':').toUpperCase());
            }
            ClientLogonRsp rsp = c.start(logon);
            if (rsp == null || !rsp.isSuccess()) {
                try {
                    c.close();
                } catch (Exception ignore) {
                    // ignore
                }
                throw new IllegalStateException("OES 登录失败 rsp=" + rsp);
            }
            client = c;
            lastInMsgSeq = rsp.getLastInMsgSeq();
            applVerId.set(rsp.getApplVerId());
            lastError.set(null);
            log.info("[oes] 登录成功 applVerId={} lastInMsgSeq={} credSource={}",
                    rsp.getApplVerId(), lastInMsgSeq, cred.getSource());
            try {
                doRptSync(lastInMsgSeq);
            } catch (Exception syncEx) {
                // 不关闭连接：查询通道仍可查资金/持仓；报撤仍要求 rptSynced
                rptSynced.set(false);
                lastError.set(syncEx.getMessage());
                log.warn("[oes] 回报同步失败，降级为仅查询通道: {}", syncEx.getMessage());
            }
        }
    }

    @Override
    public Map<String, Object> probeLogon(String username, String password) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            m.put("ok", false);
            m.put("message", "用户名或密码为空");
            return m;
        }
        Path cfg = resolveOesConfig();
        if (!Files.isRegularFile(cfg)) {
            m.put("ok", false);
            m.put("message", "缺少 OES 配置: " + cfg.toAbsolutePath());
            return m;
        }
        OesClientImpl c = null;
        try {
            String driver = envOr("QUANT_KUANGRUI_DRIVER_ID", "DAEB7F56");
            c = new OesClientImpl(1, cfg.toAbsolutePath().toString());
            c.initCallBack(buildCallback());
            ClientLogonReq logon = new ClientLogonReq();
            logon.setHeartBtInt(30);
            logon.setUsername(username.trim());
            logon.setPassword(password);
            logon.setClientDriverId(driver);
            logon.setBusinessType(OesBusinessType.OES_BUSINESS_TYPE_STOCK);
            Integer enc = resolveEncryptType();
            if (enc != null) {
                logon.setLogonEncryptType(OesLogonEncryptType.valueOf(enc.intValue()));
            }
            String ip = env("QUANT_KUANGRUI_CLIENT_IP");
            String mac = env("QUANT_KUANGRUI_CLIENT_MAC");
            if (ip != null) {
                logon.setClientIp(ip);
            }
            if (mac != null) {
                logon.setClientMac(mac.replace('-', ':').toUpperCase());
            }
            ClientLogonRsp rsp = c.start(logon);
            if (rsp == null || !rsp.isSuccess()) {
                m.put("ok", false);
                m.put("message", "柜台登录失败 rsp=" + rsp);
                return m;
            }
            m.put("ok", true);
            m.put("applVerId", rsp.getApplVerId());
            m.put("message", "柜台验柜成功");
            return m;
        } catch (Exception e) {
            m.put("ok", false);
            m.put("message", "柜台验柜异常: " + e.getMessage());
            return m;
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignore) {
                    // ignore
                }
            }
        }
    }

    private KuangruiCredentials resolveCred() {
        KuangruiCredentialStore store = credentialStoreProvider.getIfAvailable();
        if (store != null) {
            return store.resolve();
        }
        String user = env("QUANT_KUANGRUI_USER");
        String pass = env("QUANT_KUANGRUI_PASSWORD");
        if (user != null && pass != null) {
            return new KuangruiCredentials(user, pass, "env");
        }
        return new KuangruiCredentials(null, null, "none");
    }

    private OesCallBack buildCallback() {
        if (OesCallBack.class.isInterface()) {
            return (OesCallBack) Proxy.newProxyInstance(
                    OesCallBack.class.getClassLoader(),
                    new Class[]{OesCallBack.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            handleCallback(method.getName(), args);
                            Class<?> rt = method.getReturnType();
                            if (rt == Void.TYPE) {
                                return null;
                            }
                            if (rt == boolean.class) {
                                return Boolean.FALSE;
                            }
                            if (rt.isPrimitive()) {
                                return 0;
                            }
                            return null;
                        }
                    });
        }
        return new OesCallBack() {
        };
    }

    private void handleCallback(String name, Object[] args) {
        if (name == null) {
            return;
        }
        String n = name.toLowerCase();
        if (n.contains("disconn")) {
            log.warn("[oes] 连接中断");
            rptSynced.set(false);
            lastError.set("disconnected");
            client = null;
            return;
        }
        if (args == null || args.length == 0) {
            return;
        }
        Object body = args[0];
        if (n.contains("trd") || n.contains("trade")) {
            enqueueTrade(body);
        } else if (n.contains("ord") || n.contains("order") || n.contains("rpt")) {
            enqueueOrder(body);
        }
    }

    private void enqueueOrder(Object item) {
        try {
            String code = str(invokeGetter(item, "getSecurityId"));
            if (code == null || code.isEmpty()) {
                code = str(invokeGetter(item, "getSecurityID"));
            }
            eventQueue.offer(new OesOrderEvent(
                    OesOrderEvent.Kind.ORDER,
                    (int) lng(invokeGetter(item, "getClSeqNo")),
                    lng(invokeGetter(item, "getClOrdId")),
                    OesViewMapper.normalizeCode(code),
                    toStatusInt(invokeGetter(item, "getOrdStatus")),
                    (int) lng(invokeGetter(item, "getCumQty")),
                    0,
                    null
            ));
        } catch (Exception e) {
            log.debug("[oes] enqueueOrder: {}", e.getMessage());
        }
    }

    private void enqueueTrade(Object item) {
        try {
            String code = str(invokeGetter(item, "getSecurityId"));
            if (code == null || code.isEmpty()) {
                code = str(invokeGetter(item, "getSecurityID"));
            }
            long px = lng(invokeGetter(item, "getTrdPrice"));
            eventQueue.offer(new OesOrderEvent(
                    OesOrderEvent.Kind.TRADE,
                    (int) lng(invokeGetter(item, "getClSeqNo")),
                    lng(invokeGetter(item, "getClOrdId")),
                    OesViewMapper.normalizeCode(code),
                    -1,
                    (int) lng(invokeGetter(item, "getCumQty")),
                    (int) lng(invokeGetter(item, "getTrdQty")),
                    KuangruiPriceScale.toYuan(px)
            ));
        } catch (Exception e) {
            log.debug("[oes] enqueueTrade: {}", e.getMessage());
        }
    }

    private void setBean(Object target, String setter, Object value) {
        if (target == null || value == null) {
            return;
        }
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(setter) || m.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> pt = m.getParameterTypes()[0];
            try {
                Object arg = coerceArg(value, pt);
                if (arg == null && pt.isPrimitive()) {
                    continue;
                }
                m.invoke(target, arg);
                return;
            } catch (Exception e) {
                log.debug("[oes] setBean {} 失败: {}", setter, e.getMessage());
            }
        }
    }

    private static Object coerceArg(Object value, Class<?> pt) {
        if (pt.isInstance(value)) {
            return value;
        }
        if (pt.isEnum() && value instanceof Number) {
            int v = ((Number) value).intValue();
            try {
                Method valueOf = pt.getMethod("valueOf", int.class);
                return valueOf.invoke(null, Integer.valueOf(v));
            } catch (Exception ignore) {
                // try name/ordinal
            }
            Object[] constants = pt.getEnumConstants();
            if (constants != null) {
                for (Object c : constants) {
                    try {
                        Method vm = c.getClass().getMethod("value");
                        Object cv = vm.invoke(c);
                        if (cv instanceof Number && ((Number) cv).intValue() == v) {
                            return c;
                        }
                    } catch (Exception ignore) {
                        // ignore
                    }
                }
                if (v >= 0 && v < constants.length) {
                    return constants[v];
                }
            }
        }
        if ((pt == Integer.TYPE || pt == Integer.class) && value instanceof Number) {
            return Integer.valueOf(((Number) value).intValue());
        }
        if ((pt == Long.TYPE || pt == Long.class) && value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        if ((pt == Byte.TYPE || pt == Byte.class) && value instanceof Number) {
            return Byte.valueOf(((Number) value).byteValue());
        }
        if ((pt == Short.TYPE || pt == Short.class) && value instanceof Number) {
            return Short.valueOf(((Number) value).shortValue());
        }
        if (pt == String.class) {
            return String.valueOf(value);
        }
        return null;
    }

    private void doRptSync(long seq) throws Exception {
        OesClientImpl c = client;
        if (c == null) {
            throw new IllegalStateException("OES 客户端为空");
        }
        // Demo：登录后应 sendRptSync；签名/异常明细见 OesRptSyncInvoker（subscribeEnvId≤0=全部）
        OesRptSyncInvoker.Result r = OesRptSyncInvoker.invoke(c, seq, 0);
        if (!r.ok) {
            log.warn("[oes] sendRptSync 失败 seq={} detail={}", seq, r.detail);
            throw new IllegalStateException(r.detail != null ? r.detail
                    : "回报同步失败（OesRptSyncInvoker/v2；请核对 -Pkuangrui 与 rpt 通道）");
        }
        rptSynced.set(true);
        lastError.set(null);
        log.info("[oes] sendRptSync 完成 lastInMsgSeq={} via {}", seq, r.methodUsed);
    }

    private Object invokeReturning(Object target, String name, Object... args) {
        Method[] methods = target.getClass().getMethods();
        for (Method m : methods) {
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != args.length) {
                continue;
            }
            // 单参枚举且传入 null：逐常量试，禁止直接传 null（同 QueryMode NPE）
            if (args.length == 1 && args[0] == null && pts[0].isEnum()) {
                Object hit = invokeWithEnumModes(target, m, pts[0]);
                if (hit != null) {
                    return hit;
                }
                continue;
            }
            boolean skip = false;
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null && (pts[i].isEnum() || pts[i].isPrimitive())) {
                    log.warn("[oes] 跳过 {}{}：禁止对 {} 传 null",
                            name, formatPts(pts), pts[i].getSimpleName());
                    skip = true;
                    break;
                }
            }
            if (skip) {
                continue;
            }
            try {
                Object[] callArgs = new Object[args.length];
                boolean ok = true;
                for (int i = 0; i < args.length; i++) {
                    if (args[i] == null) {
                        callArgs[i] = null;
                    } else if (pts[i].isInstance(args[i])) {
                        callArgs[i] = args[i];
                    } else {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                return m.invoke(target, callArgs);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof NullPointerException || cause instanceof IllegalArgumentException) {
                    log.error("[oes] invoke {}{} 入参异常: {}", name, formatPts(pts), cause.toString(), cause);
                } else {
                    log.debug("[oes] invoke {} 失败: {}", name, cause.getMessage());
                }
            }
        }
        // 再试：无参
        if (args.length > 0) {
            try {
                Method m0 = target.getClass().getMethod(name);
                return m0.invoke(target);
            } catch (Exception ignore) {
                // ignore
            }
        }
        return null;
    }

    private Object invokeWithEnumModes(Object target, Method m, Class<?> enumType) {
        Object[] constants = enumType.getEnumConstants();
        if (constants == null) {
            return null;
        }
        Object empty = null;
        for (Object mode : constants) {
            try {
                Object ret = m.invoke(target, mode);
                if (ret != null) {
                    return ret;
                }
                empty = ret;
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof NullPointerException || cause instanceof IllegalArgumentException) {
                    log.error("[oes] invoke {}#{} 异常: {}", m.getName(), mode, cause.toString(), cause);
                }
            }
        }
        return empty;
    }

    private static String formatPts(Class<?>[] pts) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(pts[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    private static Object newInstance(String className) {
        try {
            return Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            log.debug("[oes] 无法实例化 {}: {}", className, e.getMessage());
            return null;
        }
    }

    private static Object invokeGetter(Object target, String getter) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(getter);
            return m.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long lng(Object o) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o instanceof Enum) {
            try {
                Method m = o.getClass().getMethod("value");
                Object v = m.invoke(o);
                if (v instanceof Number) {
                    return ((Number) v).longValue();
                }
            } catch (Exception ignore) {
                // fallthrough
            }
            try {
                Method m = o.getClass().getMethod("getValue");
                Object v = m.invoke(o);
                if (v instanceof Number) {
                    return ((Number) v).longValue();
                }
            } catch (Exception ignore) {
                // fallthrough
            }
        }
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static int toStatusInt(Object st) {
        if (st == null) {
            return -1;
        }
        if (st instanceof Number) {
            return ((Number) st).intValue();
        }
        if (st instanceof Enum) {
            try {
                Method m = st.getClass().getMethod("value");
                Object v = m.invoke(st);
                if (v instanceof Number) {
                    return ((Number) v).intValue();
                }
            } catch (Exception ignore) {
                // fallthrough
            }
            return ((Enum<?>) st).ordinal();
        }
        try {
            return Integer.parseInt(st.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void closeClient() {
        OesClientImpl c = client;
        client = null;
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                log.debug("[oes] close: {}", e.getMessage());
            }
        }
    }

    private Path resolveOesConfig() {
        QuantProperties.Kuangrui k = quantProperties.getKuangrui();
        String dir = k == null || k.getConfigDir() == null || k.getConfigDir().trim().isEmpty()
                ? "config/kuangrui/local"
                : k.getConfigDir().trim();
        String file = k == null || k.getOes() == null || k.getOes().getConfigFile() == null
                ? "oes_api_config.json"
                : k.getOes().getConfigFile().trim();
        return Paths.get(dir, file);
    }

    private Integer resolveEncryptType() {
        QuantProperties.Kuangrui k = quantProperties.getKuangrui();
        if (k == null || k.getOes() == null) {
            return null;
        }
        return k.getOes().getEncryptType();
    }

    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        return v.trim();
    }

    private static String envOr(String key, String def) {
        String v = env(key);
        return v == null ? def : v;
    }
}
