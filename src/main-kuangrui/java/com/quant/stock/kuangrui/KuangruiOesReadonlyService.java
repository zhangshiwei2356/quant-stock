package com.quant.stock.kuangrui;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.trade.dto.OrderDTO;
import com.quant360.api.callback.OesCallBack;
import com.quant360.api.client.Client;
import com.quant360.api.client.impl.OesClientImpl;
import com.quant360.api.model.ClientLogonReq;
import com.quant360.api.model.ClientLogonRsp;
import com.quant360.api.model.oes.OesCashTrsfReq;
import com.quant360.api.model.oes.OesClientOverview;
import com.quant360.api.model.oes.OesOrdCancelReq;
import com.quant360.api.model.oes.OesOrdReq;
import com.quant360.api.model.oes.OesQryCashAssetFilter;
import com.quant360.api.model.oes.OesQryCashAssetRsp;
import com.quant360.api.model.oes.OesQryCashTransferSerialFilter;
import com.quant360.api.model.oes.OesQryCashTransferSerialRsp;
import com.quant360.api.model.oes.OesQryCommissionRateFilter;
import com.quant360.api.model.oes.OesQryCommissionRateRsp;
import com.quant360.api.model.oes.OesQryCounterCashFilter;
import com.quant360.api.model.oes.OesQryCounterCashRsp;
import com.quant360.api.model.oes.OesQryInvAcctFilter;
import com.quant360.api.model.oes.OesQryInvAcctRsp;
import com.quant360.api.model.oes.OesQryOrdFilter;
import com.quant360.api.model.oes.OesQryOrdRsp;
import com.quant360.api.model.oes.OesQryStkHoldingFilter;
import com.quant360.api.model.oes.OesQryStkHoldingRsp;
import com.quant360.api.model.oes.OesQryStockFilter;
import com.quant360.api.model.oes.OesQryStockRsp;
import com.quant360.api.model.oes.OesQryTradingDayRsp;
import com.quant360.api.model.oes.OesQryTrdFilter;
import com.quant360.api.model.oes.OesQryTrdRsp;
import com.quant360.api.model.oes.enu.OesBusinessType;
import com.quant360.api.model.oes.enu.OesBuySellType;
import com.quant360.api.model.oes.enu.OesCashDirect;
import com.quant360.api.model.oes.enu.OesCashTrsfType;
import com.quant360.api.model.oes.enu.OesLogonEncryptType;
import com.quant360.api.model.oes.enu.OesMarketId;
import com.quant360.api.model.oes.enu.OesOrdType;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 宽睿 OES：登录 + {@code sendRptSync} + 只读查询（M2）；可选报撤与回报队列（M3，{@code oes.order-enabled}）。
 * 仅 {@code -Pkuangrui} 编译；查询/报撤/银证主路径为强类型 Filter/Req + {@code Client.QueryMode.ALL}。
 * <p>
 * M5b：断线异步 close → 懒重连 + {@code sendRptSync}；回调内勿重活。
 * </p>
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

    private final QuantProperties quantProperties;
    private final org.springframework.beans.factory.ObjectProvider<KuangruiCredentialStore> credentialStoreProvider;

    private final Object clientLock = new Object();
    private final AtomicBoolean rptSynced = new AtomicBoolean(false);
    private final AtomicBoolean disconnected = new AtomicBoolean(false);
    private final AtomicBoolean cleanupScheduled = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<String>();
    private final AtomicReference<String> applVerId = new AtomicReference<String>();
    private final AtomicInteger disconnectCount = new AtomicInteger(0);
    private final AtomicInteger reconnectCount = new AtomicInteger(0);
    private final ConcurrentLinkedQueue<OesOrderEvent> eventQueue = new ConcurrentLinkedQueue<OesOrderEvent>();
    private final ScheduledExecutorService disconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "oes-disconnect");
        t.setDaemon(true);
        return t;
    });
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
        boolean connected = client != null && !disconnected.get();
        m.put("live", true);
        m.put("orderLive", isOrderLive());
        m.put("impl", "kuangrui-oes");
        m.put("loggedIn", client != null);
        m.put("connected", connected);
        m.put("disconnected", disconnected.get());
        m.put("disconnectCount", disconnectCount.get());
        m.put("reconnectCount", reconnectCount.get());
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
            log.error("[oes] 就绪失败: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public List<Map<String, Object>> queryCash() {
        ensureReadyOrThrow();
        List<?> raw = Collections.emptyList();
        try {
            OesQryCashAssetRsp rsp = client.queryCashAsset(new OesQryCashAssetFilter(), Client.QueryMode.ALL);
            if (rsp != null && rsp.getQryItems() != null) {
                raw = rsp.getQryItems();
            }
            log.info("[oes] queryCashAsset 返回 {} 条", raw.size());
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[oes] queryCashAsset 失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
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
        List<?> raw = Collections.emptyList();
        try {
            OesQryStkHoldingRsp rsp = client.queryStkHolding(new OesQryStkHoldingFilter(), Client.QueryMode.ALL);
            if (rsp != null && rsp.getQryItems() != null) {
                raw = rsp.getQryItems();
            }
            log.info("[oes] queryStkHolding 返回 {} 条", raw.size());
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[oes] queryStkHolding 失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
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
        List<?> raw = Collections.emptyList();
        try {
            OesQryOrdRsp rsp = client.queryOrder(new OesQryOrdFilter(), Client.QueryMode.ALL);
            if (rsp != null && rsp.getQryItems() != null) {
                raw = rsp.getQryItems();
            }
            log.info("[oes] queryOrder 返回 {} 条", raw.size());
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[oes] queryOrder 失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
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
        List<?> raw = Collections.emptyList();
        try {
            OesQryTrdRsp rsp = client.queryTrade(new OesQryTrdFilter(), Client.QueryMode.ALL);
            if (rsp != null && rsp.getQryItems() != null) {
                raw = rsp.getQryItems();
            }
            log.info("[oes] queryTrade 返回 {} 条", raw.size());
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[oes] queryTrade 失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
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
        String norm = OesViewMapper.normalizeCode(code);
        OesQryStockFilter filter = new OesQryStockFilter();
        if (norm != null && !norm.isEmpty()) {
            filter.setSecurityId(norm);
            OesMarketId mktId = toOesMarket(KuangruiExchangeIds.fromStockCode(norm));
            if (mktId != null) {
                filter.setMktId(mktId);
            }
        }
        List<?> raw = Collections.emptyList();
        try {
            OesQryStockRsp rsp = client.queryStock(filter, Client.QueryMode.ALL);
            if (rsp != null && rsp.getQryItems() != null) {
                raw = rsp.getQryItems();
            }
            log.info("[oes] queryStock 返回 {} 条", raw.size());
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[oes] queryStock 失败: {}", e.getMessage(), e);
            return Collections.emptyList();
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
        int day = 0;
        try {
            OesQryTradingDayRsp rsp = client.queryTradingDay();
            if (rsp != null) {
                day = rsp.getTradingDay();
            }
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[oes] queryTradingDay 失败: {}", e.getMessage(), e);
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
        List<?> raw = Collections.emptyList();
        try {
            OesQryCommissionRateRsp rsp =
                    client.queryCommissionRate(new OesQryCommissionRateFilter(), Client.QueryMode.ALL);
            if (rsp != null && rsp.getQryItems() != null) {
                raw = rsp.getQryItems();
            }
            log.info("[oes] queryCommissionRate 返回 {} 条", raw.size());
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[oes] queryCommissionRate 失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
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

    @Override
    public Map<String, Object> queryClientOverview() {
        ensureReadyOrThrow();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        try {
            OesClientOverview ov = client.queryClientOverview();
            if (ov == null) {
                m.put("ok", false);
                m.put("message", "queryClientOverview 返回 null");
                return m;
            }
            m.put("ok", true);
            m.put("clientId", lng(firstGetter(ov, "getClientId")));
            m.put("clientName", str(firstGetter(ov, "getClientName")));
            m.put("clientMemo", str(firstGetter(ov, "getClientMemo")));
            m.put("clientType", enumName(firstGetter(ov, "getClientType")));
            m.put("clientStatus", enumName(firstGetter(ov, "getClientStatus")));
            m.put("apiForbidden", Boolean.TRUE.equals(firstGetter(ov, "isApiForbidden")));
            m.put("logonTime", lng(firstGetter(ov, "getLogonTime")));
            m.put("currOrdConnected", lng(firstGetter(ov, "getCurrOrdConnected")));
            m.put("currRptConnected", lng(firstGetter(ov, "getCurrRptConnected")));
            m.put("currQryConnected", lng(firstGetter(ov, "getCurrQryConnected")));
            m.put("associatedCustCnt", lng(firstGetter(ov, "getAssociatedCustCnt")));
            m.put("applVerId", applVerId.get());
            List<Map<String, Object>> custs = new ArrayList<Map<String, Object>>();
            Object custItems = firstGetter(ov, "getCustItems");
            if (custItems instanceof List) {
                for (Object c : (List<?>) custItems) {
                    if (c == null) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("custId", str(firstGetter(c, "getCustId")));
                    row.put("custName", str(firstGetter(c, "getCustName")));
                    row.put("status", enumName(firstGetter(c, "getStatus")));
                    row.put("cashAcctId", str(firstGetter(
                            firstGetter(c, "getSpotCashAcct", "getCashAcct"), "getCashAcctId")));
                    row.put("shInvAcctId", str(firstGetter(
                            firstGetter(c, "getShSpotInvAcct", "getShInvAcct"), "getInvAcctId")));
                    row.put("szInvAcctId", str(firstGetter(
                            firstGetter(c, "getSzSpotInvAcct", "getSzInvAcct"), "getInvAcctId")));
                    custs.add(row);
                }
            }
            m.put("custItems", custs);
            m.put("count", custs.size());
            return m;
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[oes] queryClientOverview 失败: {}", e.getMessage(), e);
            m.put("ok", false);
            m.put("message", e.getMessage());
            return m;
        }
    }

    @Override
    public List<Map<String, Object>> queryInvAcct() {
        ensureReadyOrThrow();
        List<?> raw = Collections.emptyList();
        try {
            OesQryInvAcctRsp rsp = client.queryInvAcct(new OesQryInvAcctFilter(), Client.QueryMode.ALL);
            if (rsp != null && rsp.getQryItems() != null) {
                raw = rsp.getQryItems();
            }
            log.info("[oes] queryInvAcct 返回 {} 条", raw.size());
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[oes] queryInvAcct 失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : raw) {
            out.add(OesViewMapper.invAcct(
                    str(firstGetter(item, "getInvAcctId")),
                    str(firstGetter(item, "getCustId")),
                    (int) lng(firstGetter(item, "getMktId")),
                    enumName(firstGetter(item, "getStatus")),
                    Boolean.TRUE.equals(firstGetter(item, "isTradeDisabled")),
                    (int) lng(firstGetter(item, "getPbuId")),
                    (int) lng(firstGetter(item, "getSubscriptionQuota"))
            ));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> queryCounterCash(String cashAcctId) {
        ensureReadyOrThrow();
        OesQryCounterCashFilter filter = new OesQryCounterCashFilter();
        String acct = cashAcctId == null ? null : cashAcctId.trim();
        if (acct != null && !acct.isEmpty()) {
            filter.setCashAcctId(acct);
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        try {
            OesQryCounterCashRsp rsp = client.queryCounterCash(filter);
            Object item = rsp == null ? null : rsp.getCounterCashItem();
            if (item == null) {
                log.info("[oes] queryCounterCash 返回空");
                return out;
            }
            out.add(OesViewMapper.counterCash(
                    str(firstGetter(item, "getCashAcctId")),
                    str(firstGetter(item, "getCustId")),
                    str(firstGetter(item, "getCustName")),
                    str(firstGetter(item, "getBankId")),
                    lng(firstGetter(item, "getCounterAvailableBal")),
                    lng(firstGetter(item, "getCounterDrawableBal")),
                    Boolean.TRUE.equals(firstGetter(item, "isCashTrsfDisabled"))
            ));
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[oes] queryCounterCash 失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
        return out;
    }

    /**
     * 可买/可卖量：{@code OesQryMaxTradableQtyReq}/{@code queryMaxTradableQty} 不在当前 OesClient 公开 API
     * （含 0.17/0.19 资料包）；保留反射以便未来 0.19+ 扩展 jar 出现时仍可探测。
     */
    @Override
    public Map<String, Object> queryMaxTradableQty(String code, String side, BigDecimal priceYuan) {
        ensureReadyOrThrow();
        Map<String, Object> fail = new LinkedHashMap<String, Object>();
        String norm = OesViewMapper.normalizeCode(code);
        if (norm == null || norm.isEmpty()) {
            fail.put("ok", false);
            fail.put("message", "code 不能为空");
            return fail;
        }
        if (priceYuan == null || priceYuan.compareTo(BigDecimal.ZERO) <= 0) {
            fail.put("ok", false);
            fail.put("message", "price 须为正");
            return fail;
        }
        boolean sell = side != null && "SELL".equalsIgnoreCase(side.trim());
        try {
            Object req = newInstance("com.quant360.api.model.oes.OesQryMaxTradableQtyReq");
            if (req == null) {
                fail.put("ok", false);
                fail.put("message", "无法创建 OesQryMaxTradableQtyReq（需 0.19+ API）");
                return fail;
            }
            setBean(req, "setSecurityId", norm);
            int mkt = KuangruiExchangeIds.fromStockCode(norm);
            OesMarketId mktEnum = toOesMarket(mkt);
            if (mktEnum != null) {
                setBean(req, "setMktId", mktEnum);
            } else if (mkt > 0) {
                setBean(req, "setMktId", Integer.valueOf(mkt));
            }
            setBean(req, "setBsType", toOesBsType(sell));
            int pxMilli = KuangruiPriceScale.toMilliInt(priceYuan);
            setBean(req, "setOrdPrice", Integer.valueOf(pxMilli));

            Object ret = invokeReturning(client, "queryMaxTradableQty", req);
            Object item = ret;
            if (ret != null) {
                Object nested = firstGetter(ret, "getMaxTradableQtyItem");
                if (nested != null) {
                    item = nested;
                }
            }
            if (item == null) {
                fail.put("ok", false);
                fail.put("message", "queryMaxTradableQty 返回空");
                fail.put("code", norm);
                return fail;
            }
            Map<String, Object> m = OesViewMapper.maxTradableQty(
                    str(firstGetter(item, "getSecurityId", "getSecurityID")),
                    sell ? "SELL" : "BUY",
                    lng(firstGetter(item, "getOrdPrice")),
                    lng(firstGetter(item, "getMinTradableQty")),
                    lng(firstGetter(item, "getMaxTradableQty"))
            );
            if (m.get("code") == null || String.valueOf(m.get("code")).isEmpty()) {
                m.put("code", norm);
            }
            return m;
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[oes] queryMaxTradableQty 失败: {}", e.getMessage(), e);
            fail.put("ok", false);
            fail.put("message", e.getMessage());
            fail.put("code", norm);
            return fail;
        }
    }

    @Override
    public List<Map<String, Object>> queryCashTransferSerial(String cashAcctId) {
        ensureReadyOrThrow();
        OesQryCashTransferSerialFilter filter = new OesQryCashTransferSerialFilter();
        String acct = cashAcctId == null ? null : cashAcctId.trim();
        if (acct != null && !acct.isEmpty()) {
            filter.setCashAcctId(acct);
        }
        List<?> raw = Collections.emptyList();
        try {
            OesQryCashTransferSerialRsp rsp = client.queryCashTransferSerial(filter, Client.QueryMode.ALL);
            if (rsp != null && rsp.getQryItems() != null) {
                raw = rsp.getQryItems();
            }
            log.info("[oes] queryCashTransferSerial 返回 {} 条", raw.size());
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[oes] queryCashTransferSerial 失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : raw) {
            out.add(OesViewMapper.cashTransfer(
                    (int) lng(firstGetter(item, "getClSeqNo")),
                    str(firstGetter(item, "getCashAcctId")),
                    enumName(firstGetter(item, "getDirect")),
                    enumName(firstGetter(item, "getTrsfType")),
                    enumName(firstGetter(item, "getTrsfStatus")),
                    lng(firstGetter(item, "getOccurAmt")),
                    (int) lng(firstGetter(item, "getCounterEntrustNo")),
                    (int) lng(firstGetter(item, "getRejReason")),
                    str(firstGetter(item, "getRejReasonInfo")),
                    str(firstGetter(item, "getAllotSerialNo")),
                    (int) lng(firstGetter(item, "getOperDate")),
                    (int) lng(firstGetter(item, "getOperTime"))
            ));
        }
        return out;
    }

    private static String enumName(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof Enum) {
            return ((Enum<?>) o).name();
        }
        return String.valueOf(o);
    }

    private static OesMarketId toOesMarket(int mkt) {
        if (mkt == KuangruiExchangeIds.SZSE) {
            return OesMarketId.OES_MKT_ID_SZ_A;
        }
        if (mkt == KuangruiExchangeIds.BSE) {
            return OesMarketId.OES_MKT_EXT_BJ;
        }
        if (mkt == KuangruiExchangeIds.SSE) {
            return OesMarketId.OES_MKT_ID_SH_A;
        }
        return null;
    }

    private static OesBuySellType toOesBsType(boolean sell) {
        return sell ? OesBuySellType.OES_BS_TYPE_S : OesBuySellType.OES_BS_TYPE_B;
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
            log.error("OES 只读通道异常", e);
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
            OesMarketId mktId = toOesMarket(mkt);
            if (mktId == null || qty < 100 || priceYuan == null) {
                return OesPlaceResult.fail(clSeqNo, "非法标的/数量/价格");
            }
            int pxMilli = KuangruiPriceScale.toMilliInt(priceYuan);
            if (pxMilli <= 0) {
                return OesPlaceResult.fail(clSeqNo, "价格毫级无效");
            }
            OesOrdReq req = new OesOrdReq();
            req.setClSeqNo(clSeqNo);
            req.setMktId(mktId);
            req.setOrdType(OesOrdType.valueOf(mktId, 0));
            req.setBsType(side == OrderDTO.Side.SELL
                    ? OesBuySellType.OES_BS_TYPE_S
                    : OesBuySellType.OES_BS_TYPE_B);
            req.setSecurityId(code);
            req.setOrdQty(qty);
            req.setOrdPrice(pxMilli);
            client.sendOrdReq(req);
            log.info("[oes] 报单已发 clSeqNo={} {} {}@{} x{} clientId={}",
                    clSeqNo, side, code, priceYuan, qty, clientOrderId);
            lastError.set(null);
            return OesPlaceResult.ok(clSeqNo, 0L);
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[oes] 报单失败 clSeqNo={}: {}", clSeqNo, e.getMessage(), e);
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
                log.error("[oes] 回报未同步，拒绝撤单 lastError={}", lastError.get());
                return false;
            }
            String code = OesViewMapper.normalizeCode(stockCode);
            int mkt = KuangruiExchangeIds.fromStockCode(code);
            OesOrdCancelReq req = new OesOrdCancelReq();
            req.setClSeqNo(nextInternalCancelSeq(origClSeqNo));
            req.setOrigClSeqNo(origClSeqNo);
            OesMarketId mktId = toOesMarket(mkt);
            if (mktId != null) {
                req.setMktId(mktId);
            }
            if (code != null && !code.isEmpty()) {
                req.setSecurityId(code);
            }
            client.sendOrdCancelReq(req);
            log.info("[oes] 撤单已发 origClSeqNo={} code={}", origClSeqNo, code);
            return true;
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[oes] 撤单失败 origClSeqNo={}: {}", origClSeqNo, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public OesPlaceResult sendCashTrsf(int clSeqNo, String direct, BigDecimal amountYuan,
                                       String cashAcctId, String trsfType,
                                       String trdPasswd, String trsfPasswd) {
        if (!isOrderLive()) {
            return OesPlaceResult.fail(clSeqNo, "oes.order-enabled=false");
        }
        try {
            ensureReadyOrThrow();
            if (!rptSynced.get()) {
                return OesPlaceResult.fail(clSeqNo, "回报未同步(rptSynced=false)，禁止银证；可先查资金。详情: "
                        + lastError.get());
            }
            if (amountYuan == null || amountYuan.compareTo(BigDecimal.ZERO) <= 0) {
                return OesPlaceResult.fail(clSeqNo, "amount 须为正");
            }
            long amtMilli = KuangruiPriceScale.toMilliLong(amountYuan);
            if (amtMilli <= 0L) {
                return OesPlaceResult.fail(clSeqNo, "金额毫级无效");
            }
            OesCashDirect dirEnum = toOesCashDirect(direct);
            if (dirEnum == null) {
                return OesPlaceResult.fail(clSeqNo, "direct 须为 IN 或 OUT");
            }
            OesCashTrsfType typeEnum = toOesCashTrsfType(trsfType);
            if (typeEnum == null) {
                return OesPlaceResult.fail(clSeqNo, "trsfType 无效（可用 BANK/COUNTER/COUNTER_BANK/OES_TO_OES）");
            }
            OesCashTrsfReq req = new OesCashTrsfReq();
            req.setClSeqNo(clSeqNo);
            req.setDirect(dirEnum);
            req.setTrsfType(typeEnum);
            req.setOccurAmt(amtMilli);
            String acct = cashAcctId == null ? null : cashAcctId.trim();
            if (acct != null && !acct.isEmpty()) {
                req.setCashAcctId(acct);
            }
            // 密码仅写入请求，不落日志/响应
            if (trdPasswd != null && !trdPasswd.isEmpty()) {
                req.setTrdPasswd(trdPasswd);
            }
            if (trsfPasswd != null && !trsfPasswd.isEmpty()) {
                req.setTrsfPasswd(trsfPasswd);
            }
            client.sendCashTrsfReq(req);
            log.info("[oes] 银证已发 clSeqNo={} direct={} trsfType={} amountYuan={} cashAcctId={}",
                    clSeqNo, dirEnum.name(), typeEnum.name(), amountYuan,
                    acct == null ? "" : acct);
            lastError.set(null);
            return OesPlaceResult.ok(clSeqNo, 0L);
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[oes] 银证失败 clSeqNo={}: {}", clSeqNo, e.getMessage(), e);
            return OesPlaceResult.fail(clSeqNo, e.getMessage());
        }
    }

    private static OesCashDirect toOesCashDirect(String direct) {
        if (direct == null) {
            return null;
        }
        String directKey = direct.trim().toUpperCase();
        if (directKey.isEmpty()) {
            return null;
        }
        if ("IN".equals(directKey) || "OES_CASH_DIRECT_IN".equals(directKey) || "TRANSFER_IN".equals(directKey)
                || "BANK_TO_SEC".equals(directKey)) {
            return OesCashDirect.OES_CASH_DIRECT_IN;
        }
        if ("OUT".equals(directKey) || "OES_CASH_DIRECT_OUT".equals(directKey) || "TRANSFER_OUT".equals(directKey)
                || "SEC_TO_BANK".equals(directKey)) {
            return OesCashDirect.OES_CASH_DIRECT_OUT;
        }
        return null;
    }

    /**
     * 默认 OES↔银行；别名 BANK/COUNTER/COUNTER_BANK/OES_TO_OES。
     */
    private static OesCashTrsfType toOesCashTrsfType(String trsfType) {
        String trsfTypeKey = trsfType == null ? "" : trsfType.trim().toUpperCase();
        if (trsfTypeKey.isEmpty() || "BANK".equals(trsfTypeKey) || "OES_BANK".equals(trsfTypeKey)
                || "OES_FUND_TRSF_TYPE_OES_BANK".equals(trsfTypeKey)) {
            return OesCashTrsfType.OES_FUND_TRSF_TYPE_OES_BANK;
        }
        if ("COUNTER".equals(trsfTypeKey) || "OES_COUNTER".equals(trsfTypeKey)
                || "OES_FUND_TRSF_TYPE_OES_COUNTER".equals(trsfTypeKey)) {
            return OesCashTrsfType.OES_FUND_TRSF_TYPE_OES_COUNTER;
        }
        if ("COUNTER_BANK".equals(trsfTypeKey) || "OES_FUND_TRSF_TYPE_COUNTER_BANK".equals(trsfTypeKey)) {
            return OesCashTrsfType.OES_FUND_TRSF_TYPE_COUNTER_BANK;
        }
        if ("OES_TO_OES".equals(trsfTypeKey) || "OES_FUND_TRSF_TYPE_OES_TO_OES".equals(trsfTypeKey)) {
            return OesCashTrsfType.OES_FUND_TRSF_TYPE_OES_TO_OES;
        }
        return null;
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
                log.error("[oes] poll 查询补强失败: {}", ex.getMessage(), ex);
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
            disconnected.set(false);
            eventQueue.clear();
            closeClient();
        }
    }

    @PreDestroy
    public void destroy() {
        stop();
        disconnectExecutor.shutdownNow();
    }

    private void ensureReadyOrThrow() {
        if (!ensureReady()) {
            throw new IllegalStateException("OES 未就绪: " + lastError.get());
        }
    }

    private void ensureClient() throws Exception {
        synchronized (clientLock) {
            if (client != null && rptSynced.get() && !disconnected.get()) {
                return;
            }
            // 断线后须先 close 再重建，避免假死连接
            if (client != null && disconnected.get()) {
                closeClient();
            }
            // 已登录：再尝试一次 sync；失败则保持查询通道（降级）
            if (client != null) {
                if (!rptSynced.get()) {
                    try {
                        doRptSync(lastInMsgSeq);
                    } catch (Exception e) {
                        lastError.set(e.getMessage());
                        log.error("[oes] 再次回报同步失败，保持查询降级: {}", e.getMessage(), e);
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
                    log.error("OES 只读通道异常", ignore);
                    // ignore
                }
                throw new IllegalStateException("OES 登录失败 rsp=" + rsp);
            }
            boolean wasReconnect = disconnectCount.get() > 0;
            client = c;
            disconnected.set(false);
            lastInMsgSeq = rsp.getLastInMsgSeq();
            applVerId.set(rsp.getApplVerId());
            lastError.set(null);
            if (wasReconnect) {
                reconnectCount.incrementAndGet();
            }
            log.info("[oes] 登录成功 applVerId={} lastInMsgSeq={} credSource={} reconnectCount={}",
                    rsp.getApplVerId(), lastInMsgSeq, cred.getSource(), reconnectCount.get());
            try {
                doRptSync(lastInMsgSeq);
            } catch (Exception syncEx) {
                // 不关闭连接：查询通道仍可查资金/持仓；报撤仍要求 rptSynced
                rptSynced.set(false);
                lastError.set(syncEx.getMessage());
                log.error("[oes] 回报同步失败，降级为仅查询通道: {}", syncEx.getMessage(), syncEx);
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
            log.error("OES 只读通道异常", e);
            m.put("ok", false);
            m.put("message", "柜台验柜异常: " + e.getMessage());
            return m;
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignore) {
                    log.error("OES 只读通道异常", ignore);
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

    private void handleCallback(String methodName, Object[] args) {
        if (methodName == null) {
            return;
        }
        String methodNameLower = methodName.toLowerCase();
        if (methodNameLower.contains("disconn")) {
            // 回调内勿重活：打标后异步 close，确保下次 ensureClient 先 close 再重建
            log.error("[oes] 连接中断");
            rptSynced.set(false);
            disconnected.set(true);
            lastError.set("disconnected");
            disconnectCount.incrementAndGet();
            scheduleDisconnectCleanup();
            return;
        }
        if (args == null || args.length == 0) {
            return;
        }
        Object body = args[0];
        if (methodNameLower.contains("trd") || methodNameLower.contains("trade")) {
            enqueueTrade(body);
        } else if (methodNameLower.contains("ord") || methodNameLower.contains("order")
                || methodNameLower.contains("rpt")) {
            enqueueOrder(body);
        }
    }

    private void scheduleDisconnectCleanup() {
        if (!cleanupScheduled.compareAndSet(false, true)) {
            return;
        }
        disconnectExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (clientLock) {
                        if (disconnected.get()) {
                            closeClient();
                        }
                    }
                } finally {
                    cleanupScheduled.set(false);
                }
            }
        });
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
            log.error("[oes] enqueueOrder: {}", e.getMessage(), e);
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
            log.error("[oes] enqueueTrade: {}", e.getMessage(), e);
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
                log.error("[oes] setBean {} 失败: {}", setter, e.getMessage(), e);
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
                log.error("OES 只读通道异常", ignore);
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
                        log.error("OES 只读通道异常", ignore);
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
            log.error("[oes] sendRptSync 失败 seq={} detail={}", seq, r.detail);
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
                log.error("[oes] invoke {} 失败: {}", name, e.getMessage(), e);
            }
        }
        // 再试：无参
        if (args.length > 0) {
            try {
                Method m0 = target.getClass().getMethod(name);
                return m0.invoke(target);
            } catch (Exception ignore) {
                log.error("OES 只读通道异常", ignore);
                // ignore
            }
        }
        return null;
    }

    private static Object newInstance(String className) {
        try {
            return Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            log.error("[oes] 无法实例化 {}: {}", className, e.getMessage(), e);
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
            log.error("OES 只读通道异常", e);
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
                log.error("OES 只读通道异常", ignore);
                // fallthrough
            }
            try {
                Method m = o.getClass().getMethod("getValue");
                Object v = m.invoke(o);
                if (v instanceof Number) {
                    return ((Number) v).longValue();
                }
            } catch (Exception ignore) {
                log.error("OES 只读通道异常", ignore);
                // fallthrough
            }
        }
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            log.error("OES 只读通道异常", e);
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
                log.error("OES 只读通道异常", ignore);
                // fallthrough
            }
            return ((Enum<?>) st).ordinal();
        }
        try {
            return Integer.parseInt(st.toString());
        } catch (NumberFormatException e) {
            log.error("OES 只读通道异常", e);
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
                log.error("[oes] close: {}", e.getMessage(), e);
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
