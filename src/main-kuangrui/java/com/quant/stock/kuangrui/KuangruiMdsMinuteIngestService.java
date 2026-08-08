package com.quant.stock.kuangrui;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.CoreMarketBarService;
import com.quant360.api.callback.MdsCallBack;
import com.quant360.api.client.Client;
import com.quant360.api.client.MdsClient;
import com.quant360.api.client.impl.MdsClientImpl;
import com.quant360.api.model.ClientLogonReq;
import com.quant360.api.model.ClientLogonRsp;
import com.quant360.api.model.mds.MdsMktDataRequestEntry;
import com.quant360.api.model.mds.MdsMktDataRequestReq;
import com.quant360.api.model.mds.MdsMktDataRequestRsp;
import com.quant360.api.model.mds.MdsMktDataSnapshotBase;
import com.quant360.api.model.mds.MdsMktDataSnapshotHead;
import com.quant360.api.model.mds.MdsQryMktDataSnapshotReq;
import com.quant360.api.model.mds.MdsQrySecurityCodeEntry;
import com.quant360.api.model.mds.MdsQrySecurityStatusReq;
import com.quant360.api.model.mds.MdsQryStockStaticInfoFilter;
import com.quant360.api.model.mds.MdsQryStockStaticInfoListFilter;
import com.quant360.api.model.mds.MdsQryStockStaticInfoListRsp;
import com.quant360.api.model.mds.MdsQryStockStaticInfoRsp;
import com.quant360.api.model.mds.MdsQryTrdSessionStatusReq;
import com.quant360.api.model.mds.MdsSecurityStatusMsg;
import com.quant360.api.model.mds.MdsStockSnapshotBody;
import com.quant360.api.model.mds.MdsStockStaticInfo;
import com.quant360.api.model.mds.MdsTradingSessionStatusMsg;
import com.quant360.api.model.mds.enu.MdsExchangeId;
import com.quant360.api.model.mds.enu.MdsMktSubscribeFlag;
import com.quant360.api.model.mds.enu.MdsSecurityType;
import com.quant360.api.model.mds.enu.MdsSubscribeDataType;
import com.quant360.api.model.mds.enu.MdsSubscribeMode;
import com.quant360.api.model.mds.enu.MdsSubscribedTickType;
import com.quant360.api.model.oes.enu.OesLogonEncryptType;
import com.quant360.api.model.oes.enu.OesSecurityType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 宽睿 MDS L1 → market_1min(MDS)。仅 {@code -Pkuangrui} 编译进主包。
 * <p>
 * M5a：断线异步 close → 退避重登 → 需则重订阅；回调内不做重活。
 * </p>
 */
@Slf4j
@Service
@Primary
@ConditionalOnClass(MdsClientImpl.class)
@ConditionalOnBean(CoreMarketBarService.class)
@ConditionalOnProperty(name = {
        "quant.kuangrui.enabled",
        "quant.kuangrui.mds.enabled"
}, havingValue = "true")
public class KuangruiMdsMinuteIngestService implements MdsMinuteIngestService {

    private static final long RECONNECT_BASE_MS = 1_000L;
    private static final long RECONNECT_MAX_MS = 60_000L;

    private final QuantProperties quantProperties;
    private final MdsMinuteAggregator aggregator;
    private final org.springframework.beans.factory.ObjectProvider<KuangruiCredentialStore> credentialStoreProvider;

    private final Object clientLock = new Object();
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean disconnected = new AtomicBoolean(false);
    private final AtomicBoolean wantSubscribe = new AtomicBoolean(false);
    private final AtomicBoolean cleanupScheduled = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<String>();
    private final AtomicInteger disconnectCount = new AtomicInteger(0);
    private final AtomicInteger reconnectCount = new AtomicInteger(0);
    private final AtomicInteger backoffAttempt = new AtomicInteger(0);
    private final AtomicLong nextReconnectAtMs = new AtomicLong(0L);
    private final AtomicReference<List<String>> lastSubscribeCodes =
            new AtomicReference<List<String>>(Collections.<String>emptyList());
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mds-reconnect");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> reconnectFuture;
    private volatile MdsClientImpl client;

    public KuangruiMdsMinuteIngestService(QuantProperties quantProperties,
                                          CoreMarketBarService coreMarketBarService,
                                          org.springframework.beans.factory.ObjectProvider<KuangruiCredentialStore> credentialStoreProvider) {
        this.quantProperties = quantProperties;
        this.aggregator = new MdsMinuteAggregator(coreMarketBarService);
        this.credentialStoreProvider = credentialStoreProvider;
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        boolean connected = client != null && !disconnected.get();
        m.put("live", true);
        m.put("impl", "kuangrui-mds");
        m.put("loggedIn", client != null);
        m.put("connected", connected);
        m.put("disconnected", disconnected.get());
        m.put("subscribed", subscribed.get());
        m.put("wantSubscribe", wantSubscribe.get());
        m.put("disconnectCount", disconnectCount.get());
        m.put("reconnectCount", reconnectCount.get());
        m.put("reconnectInProgress", reconnectFuture != null && !reconnectFuture.isDone());
        m.put("nextReconnectAtMs", nextReconnectAtMs.get());
        m.put("lastError", lastError.get());
        m.put("configPath", resolveMdsConfig().toString());
        m.put("configExists", Files.isRegularFile(resolveMdsConfig()));
        KuangruiCredentials cr = resolveCred();
        m.put("hasCred", cr.isPresent());
        m.put("credSource", cr.getSource());
        if (cr.isPresent()) {
            m.put("activeUsername", cr.getUsername());
        }
        m.putAll(aggregator.stats());
        return m;
    }

    @Override
    public int pullAndPersist(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return 0;
        }
        try {
            ensureClient();
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[mds] 登录失败: {}", e.getMessage(), e);
            return 0;
        }
        int ok = 0;
        int fail = 0;
        // M5+：优先 qrySnapshotList 批量；失败再逐只 qryMktDataSnapshot
        List<String> remain = new ArrayList<String>(codes);
        try {
            int batched = pullBySnapshotList(remain);
            if (batched > 0) {
                ok += batched;
                remain.clear();
            }
        } catch (Exception e) {
            log.error("[mds] qrySnapshotList 批量失败，回退单只查询: {}", e.getMessage(), e);
            if (looksLikeDisconnect(e)) {
                markDisconnectedAndScheduleCleanup(client, "pull-error: " + e.getMessage());
                return 0;
            }
        }
        for (String code : remain) {
            try {
                if (queryOne(code)) {
                    ok++;
                }
            } catch (Exception e) {
                fail++;
                log.error("[mds] 查询失败 code={}: {}", code, e.getMessage(), e);
                if (looksLikeDisconnect(e)) {
                    markDisconnectedAndScheduleCleanup(client, "pull-error: " + e.getMessage());
                    break;
                }
            }
        }
        if (ok == 0 && fail > 0 && disconnected.get()) {
            return 0;
        }
        return aggregator.flush(true);
    }

    /**
     * 批量快照查询（{@code qrySnapshotList}）。按交易所分批，单批最多 80 只。
     *
     * @return 成功喂入分钟桶的条数；0 表示未走批量或无有效结果
     */
    private int pullBySnapshotList(List<String> codes) throws Exception {
        if (client == null || codes == null || codes.isEmpty()) {
            return 0;
        }
        Map<Integer, List<String>> byExch = new LinkedHashMap<Integer, List<String>>();
        for (String code : codes) {
            int exch = KuangruiExchangeIds.fromStockCode(code);
            int instr = KuangruiExchangeIds.toInstrId(code);
            if (exch == 0 || instr == 0) {
                continue;
            }
            List<String> bucket = byExch.get(Integer.valueOf(exch));
            if (bucket == null) {
                bucket = new ArrayList<String>();
                byExch.put(Integer.valueOf(exch), bucket);
            }
            bucket.add(code);
        }
        if (byExch.isEmpty()) {
            return 0;
        }
        int fed = 0;
        boolean anyBatchOk = false;
        for (Map.Entry<Integer, List<String>> e : byExch.entrySet()) {
            List<String> list = e.getValue();
            for (int from = 0; from < list.size(); from += 80) {
                int to = Math.min(from + 80, list.size());
                List<String> chunk = list.subList(from, to);
                int n = querySnapshotListChunk(e.getKey().intValue(), chunk);
                if (n >= 0) {
                    anyBatchOk = true;
                    fed += n;
                }
            }
        }
        if (!anyBatchOk) {
            return 0;
        }
        log.info("[mds] qrySnapshotList 批量喂入 {} 条 / {} 标的", fed, codes.size());
        return fed;
    }

    /** @return 喂入条数；-1 表示本批调用失败（调用方回退单只） */
    private int querySnapshotListChunk(int exch, List<String> codes) {
        try {
            Object filter = newInstance("com.quant360.api.model.mds.MdsQrySnapshotListFilter");
            if (filter == null) {
                return -1;
            }
            setBean(filter, "setExchId", toExch(exch));
            setBean(filter, "setMdProductType", MdsSecurityType.MDS_SECURITY_TYPE_STOCK);
            Object mdLevel = resolveMdsMdLevel1();
            if (mdLevel != null) {
                setBean(filter, "setMdLevel", mdLevel);
            }
            List<Object> entries = new ArrayList<Object>();
            for (String code : codes) {
                Object entry = newInstance("com.quant360.api.model.mds.MdsQrySecurityCodeEntry");
                if (entry == null) {
                    continue;
                }
                setBean(entry, "setExchId", toExch(exch));
                setBean(entry, "setMdProductType", MdsSecurityType.MDS_SECURITY_TYPE_STOCK);
                setBean(entry, "setInstrId", Integer.valueOf(KuangruiExchangeIds.toInstrId(code)));
                entries.add(entry);
            }
            if (entries.isEmpty()) {
                return 0;
            }
            setBean(filter, "setSecurityCodeCnt", Integer.valueOf(entries.size()));
            setBean(filter, "setSecurityCodeList", entries);

            Object mode = resolveClientQueryModeAll();
            Object rsp;
            if (mode != null) {
                rsp = invokeReturning(client, "qrySnapshotList", filter, mode);
            } else {
                rsp = invokeReturning(client, "qrySnapshotList", filter);
            }
            if (rsp == null) {
                return -1;
            }
            Object items = firstGetter(rsp, "getQryItems", "getItems");
            if (!(items instanceof List)) {
                return -1;
            }
            int fed = 0;
            for (Object snap : (List<?>) items) {
                if (snap == null) {
                    continue;
                }
                Object head = firstGetter(snap, "getHead", "getSnapshotHead");
                Object stock = firstGetter(snap, "getStock", "getStockSnapshotBody");
                if (head instanceof MdsMktDataSnapshotHead && stock instanceof MdsStockSnapshotBody) {
                    feed((MdsMktDataSnapshotHead) head, (MdsStockSnapshotBody) stock);
                    fed++;
                } else if (snap instanceof MdsMktDataSnapshotBase) {
                    MdsMktDataSnapshotBase base = (MdsMktDataSnapshotBase) snap;
                    if (base.getHead() != null && base.getStock() != null) {
                        feed(base.getHead(), base.getStock());
                        fed++;
                    }
                }
            }
            return fed;
        } catch (Exception e) {
            log.error("[mds] qrySnapshotList chunk 失败 exch={} size={}: {}",
                    exch, codes.size(), e.getMessage(), e);
            return -1;
        }
    }

    private static Object resolveClientQueryModeAll() {
        try {
            Class<?> clz = Class.forName("com.quant360.api.client.Client$QueryMode");
            return clz.getMethod("valueOf", String.class).invoke(null, "ALL");
        } catch (Exception e) {
            return null;
        }
    }

    private static Object resolveMdsMdLevel1() {
        try {
            Class<?> clz = Class.forName("com.quant360.api.model.mds.enu.MdsMdLevel");
            return clz.getMethod("valueOf", String.class).invoke(null, "MDS_MD_LEVEL_1");
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean startSubscribe(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            lastError.set("订阅列表为空");
            return false;
        }
        synchronized (clientLock) {
            try {
                cancelScheduledReconnect();
                ensureClient();
                doSubscribe(codes);
                lastSubscribeCodes.set(new ArrayList<String>(codes));
                wantSubscribe.set(true);
                subscribed.set(true);
                lastError.set(null);
                log.info("[mds] 已订阅 L1 codes={}", codes.size());
                return true;
            } catch (Exception e) {
                lastError.set(e.getMessage());
                log.error("[mds] 订阅失败: {}", e.getMessage(), e);
                return false;
            }
        }
    }

    @Override
    public void stopSubscribe() {
        synchronized (clientLock) {
            wantSubscribe.set(false);
            subscribed.set(false);
            cancelScheduledReconnect();
            closeClient();
            disconnected.set(false);
        }
    }

    @Override
    public int flushBuckets() {
        return aggregator.flush(true);
    }

    @Override
    public List<Map<String, Object>> queryStockStatic(String code) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String norm = OesViewMapper.normalizeCode(code);
        if (norm == null || norm.isEmpty()) {
            return out;
        }
        try {
            ensureClient();
            int exch = KuangruiExchangeIds.fromStockCode(norm);
            int instr = KuangruiExchangeIds.toInstrId(norm);
            if (exch == 0 || instr == 0) {
                return out;
            }
            MdsQryStockStaticInfoFilter filter = new MdsQryStockStaticInfoFilter();
            filter.setSecurityId(norm);
            filter.setExchId(toExch(exch));
            filter.setInstrId(instr);
            filter.setSecurityType(OesSecurityType.OES_SECURITY_TYPE_STOCK);
            MdsQryStockStaticInfoRsp rsp = client.qryStockStaticInfo(filter, Client.QueryMode.ALL);
            List<MdsStockStaticInfo> items = rsp == null ? null : rsp.getQryItems();
            if (items == null || items.isEmpty()) {
                items = queryStockStaticByList(exch, instr);
            }
            if (items != null) {
                for (MdsStockStaticInfo item : items) {
                    if (item != null) {
                        out.add(mapMdsStatic(item, norm));
                    }
                }
            }
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[mds] qryStockStaticInfo {}: {}", norm, e.getMessage(), e);
        }
        return out;
    }

    /** List 接口兜底：按交易所 + 证券代码条目查询。 */
    private List<MdsStockStaticInfo> queryStockStaticByList(int exch, int instr) throws Exception {
        MdsQryStockStaticInfoListFilter listFilter = new MdsQryStockStaticInfoListFilter();
        listFilter.setExchId(toExch(exch));
        listFilter.setSecurityType(OesSecurityType.OES_SECURITY_TYPE_STOCK);
        MdsQrySecurityCodeEntry entry = new MdsQrySecurityCodeEntry();
        entry.setExchId(toExch(exch));
        entry.setMdProductType(MdsSecurityType.MDS_SECURITY_TYPE_STOCK);
        entry.setInstrId(instr);
        List<MdsQrySecurityCodeEntry> entries = new ArrayList<MdsQrySecurityCodeEntry>();
        entries.add(entry);
        listFilter.setSecurityCodeCnt(1);
        listFilter.setSecurityCodeList(entries);
        MdsQryStockStaticInfoListRsp listRsp =
                client.qryStockStaticInfoList(listFilter, Client.QueryMode.ALL);
        return listRsp == null ? null : listRsp.getQryItems();
    }

    @Override
    public List<Map<String, Object>> querySecurityStatus(String code) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String norm = OesViewMapper.normalizeCode(code);
        if (norm == null || norm.isEmpty()) {
            return out;
        }
        try {
            ensureClient();
            int exch = KuangruiExchangeIds.fromStockCode(norm);
            int instr = KuangruiExchangeIds.toInstrId(norm);
            if (exch == 0 || instr == 0) {
                return out;
            }
            MdsQrySecurityStatusReq req = new MdsQrySecurityStatusReq();
            req.setExchId(toExch(exch));
            req.setSecurityType(MdsSecurityType.MDS_SECURITY_TYPE_STOCK);
            req.setInstrId(instr);
            MdsSecurityStatusMsg msg = client.qrySecurityStatus(req);
            if (msg != null) {
                out.add(mapMdsStatus(msg, norm));
            }
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[mds] qrySecurityStatus {}: {}", norm, e.getMessage(), e);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> queryTrdSessionStatus() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        try {
            ensureClient();
            MdsExchangeId[] exchanges = new MdsExchangeId[]{
                    MdsExchangeId.MDS_EXCH_SSE,
                    MdsExchangeId.MDS_EXCH_SZSE
            };
            for (MdsExchangeId exchId : exchanges) {
                MdsQryTrdSessionStatusReq req = new MdsQryTrdSessionStatusReq();
                req.setExchId(exchId);
                req.setSecurityType(MdsSecurityType.MDS_SECURITY_TYPE_STOCK);
                MdsTradingSessionStatusMsg msg = client.qryTrdSessionStatus(req);
                if (msg != null) {
                    out.add(mapMdsSession(msg));
                }
            }
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.error("[mds] qryTrdSessionStatus: {}", e.getMessage(), e);
        }
        return out;
    }

    private Map<String, Object> mapMdsStatic(MdsStockStaticInfo item, String fallbackCode) {
        String code = item.getSecurityId();
        if (code == null || code.isEmpty()) {
            if (item.getInstrId() > 0) {
                code = String.format("%06d", item.getInstrId());
            } else {
                code = fallbackCode;
            }
        }
        long upper = item.getUpperLimitPrice();
        if (upper == 0L) {
            upper = item.getLimitUpPrice();
        }
        long lower = item.getLowerLimitPrice();
        if (lower == 0L) {
            lower = item.getLimitDownPrice();
        }
        int susp = item.getSecuritySuspFlag();
        if (susp == 0 && item.getSuspFlag() != null) {
            susp = (int) lng(item.getSuspFlag());
        }
        return MdsViewMapper.stockStatic(
                code,
                item.getSecurityName(),
                upper,
                lower,
                item.getPrevClose(),
                item.getOutstandingShare(),
                item.getPublicFloatShare(),
                susp,
                item.getSecurityStatus()
        );
    }

    private Map<String, Object> mapMdsStatus(MdsSecurityStatusMsg item, String fallbackCode) {
        String code = item.getSecurityID();
        if (code == null || code.isEmpty()) {
            if (item.getInstrId() > 0) {
                code = String.format("%06d", item.getInstrId());
            } else {
                code = fallbackCode;
            }
        }
        String fs = item.getFinancialStatus();
        // 深交所 FinancialStatus 常见含 P=停牌（运维以原始串为准）
        boolean susp = fs != null && (fs.indexOf('P') >= 0 || fs.indexOf('p') >= 0);
        Map<String, Object> m = MdsViewMapper.securityStatus(code, susp ? 1 : 0, 0, 0);
        if (fs != null && !fs.isEmpty()) {
            m.put("financialStatus", fs);
        }
        return m;
    }

    private Map<String, Object> mapMdsSession(MdsTradingSessionStatusMsg item) {
        int mktId = 0;
        if (item.getExchId() != null) {
            mktId = (int) lng(item.getExchId());
        }
        String sessionId = item.getTradingSessionID();
        int sessionStatus = 0;
        if (sessionId != null && !sessionId.isEmpty()) {
            try {
                sessionStatus = Integer.parseInt(sessionId.trim());
            } catch (NumberFormatException ignore) {
                // 非数字时段码：保留原始串
            }
        }
        Map<String, Object> m = MdsViewMapper.trdSession(mktId, 0, sessionStatus);
        if (sessionId != null && !sessionId.isEmpty()) {
            m.put("tradingSessionId", sessionId);
        }
        return m;
    }

    private void setBean(Object target, String setter, Object value) {
        if (target == null || value == null) {
            return;
        }
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(setter) || m.getParameterTypes().length != 1) {
                continue;
            }
            try {
                Class<?> pt = m.getParameterTypes()[0];
                Object arg = value;
                if (pt.isEnum() && value instanceof Number) {
                    // try valueOf(int)
                    try {
                        java.lang.reflect.Method valueOf = pt.getMethod("valueOf", int.class);
                        arg = valueOf.invoke(null, Integer.valueOf(((Number) value).intValue()));
                    } catch (Exception ignore) {
                        log.error("MDS 分钟摄入异常", ignore);
                        arg = value;
                    }
                } else if (!pt.isInstance(value) && value instanceof Number) {
                    if (pt == Integer.TYPE || pt == Integer.class) {
                        arg = Integer.valueOf(((Number) value).intValue());
                    } else if (pt == Byte.TYPE || pt == Byte.class) {
                        arg = Byte.valueOf(((Number) value).byteValue());
                    }
                }
                if (pt.isInstance(arg) || pt.isPrimitive()) {
                    m.invoke(target, arg);
                    return;
                }
            } catch (Exception e) {
                log.error("[mds] setBean {} 失败: {}", setter, e.getMessage(), e);
            }
        }
    }

    private Object invokeReturning(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) {
                continue;
            }
            try {
                return m.invoke(target, args);
            } catch (Exception e) {
                log.error("[mds] invoke {} 失败: {}", name, e.getMessage(), e);
            }
        }
        if (args.length > 0) {
            try {
                return target.getClass().getMethod(name).invoke(target);
            } catch (Exception ignore) {
                log.error("MDS 分钟摄入异常", ignore);
                // ignore
            }
        }
        return null;
    }

    private static Object newInstance(String className) {
        try {
            return Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            log.error("MDS 分钟摄入异常", e);
            return null;
        }
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

    private static Object invokeGetter(Object target, String getter) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(getter).invoke(target);
        } catch (Exception e) {
            log.error("MDS 分钟摄入异常", e);
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
                Object v = o.getClass().getMethod("value").invoke(o);
                if (v instanceof Number) {
                    return ((Number) v).longValue();
                }
            } catch (Exception ignore) {
                log.error("MDS 分钟摄入异常", ignore);
                // fallthrough
            }
            return ((Enum<?>) o).ordinal();
        }
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            log.error("MDS 分钟摄入异常", e);
            return 0L;
        }
    }

    private boolean queryOne(String code) throws Exception {
        int exch = KuangruiExchangeIds.fromStockCode(code);
        int instr = KuangruiExchangeIds.toInstrId(code);
        if (exch == 0 || instr == 0) {
            return false;
        }
        MdsQryMktDataSnapshotReq req = new MdsQryMktDataSnapshotReq();
        req.setExchId(toExch(exch));
        req.setSecurityType(MdsSecurityType.MDS_SECURITY_TYPE_STOCK);
        req.setInstrId(instr);
        MdsMktDataSnapshotBase snap = client.qryMktDataSnapshot(req);
        if (snap == null || snap.getHead() == null || snap.getStock() == null) {
            return false;
        }
        feed(snap.getHead(), snap.getStock());
        return true;
    }

    private void doSubscribe(List<String> codes) throws Exception {
        MdsMktDataRequestReq req = new MdsMktDataRequestReq();
        req.setSubMode(MdsSubscribeMode.SUB_MODE_SET);
        req.setTickType(MdsSubscribedTickType.MDS_TICK_TYPE_LATEST_SIMPLIFIED);
        req.setDataTypes(MdsSubscribeDataType.SUB_DATA_TYPE_L1_SNAPSHOT.value());
        req.setSseStockFlag(MdsMktSubscribeFlag.SUB_FLAG_DEFAULT);
        req.setSzseStockFlag(MdsMktSubscribeFlag.SUB_FLAG_DEFAULT);
        req.setBseStockFlag(MdsMktSubscribeFlag.SUB_FLAG_DEFAULT);
        req.setSseIndexFlag(MdsMktSubscribeFlag.SUB_FLAG_DISABLE);
        req.setSzseIndexFlag(MdsMktSubscribeFlag.SUB_FLAG_DISABLE);
        req.setSseOptionFlag(MdsMktSubscribeFlag.SUB_FLAG_DISABLE);
        req.setSzseOptionFlag(MdsMktSubscribeFlag.SUB_FLAG_DISABLE);
        req.setRequireInitialMktData(true);
        req.setBeginTime(0);

        List<MdsMktDataRequestEntry> entries = new ArrayList<MdsMktDataRequestEntry>();
        for (String code : codes) {
            int exch = KuangruiExchangeIds.fromStockCode(code);
            int instr = KuangruiExchangeIds.toInstrId(code);
            if (exch == 0 || instr == 0) {
                continue;
            }
            MdsMktDataRequestEntry e = new MdsMktDataRequestEntry();
            e.setExchId(toExch(exch));
            e.setSecurityType(MdsSecurityType.MDS_SECURITY_TYPE_STOCK);
            e.setInstrId(instr);
            entries.add(e);
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("无有效订阅标的");
        }
        client.subscribeMarketData(req, entries);
    }

    private void ensureClient() throws Exception {
        synchronized (clientLock) {
            if (client != null && !disconnected.get()) {
                return;
            }
            long nextAt = nextReconnectAtMs.get();
            long now = System.currentTimeMillis();
            if (client == null && disconnected.get() && nextAt > now) {
                throw new IllegalStateException("MDS 退避重连中，约 " + (nextAt - now) + "ms 后重试");
            }
            if (client != null) {
                // 断线后 client 非空却不可用：先 close 再重建，避免顶死连接
                closeClient();
            }
            Path cfg = resolveMdsConfig();
            if (!Files.isRegularFile(cfg)) {
                throw new IllegalStateException("缺少 MDS 配置: " + cfg.toAbsolutePath());
            }
            KuangruiCredentials cred = resolveCred();
            if (!cred.isPresent()) {
                throw new IllegalStateException(
                        "无宽睿账号：请在「宽睿联调 → 账号登录」验柜入库，或设置 QUANT_KUANGRUI_USER / PASSWORD");
            }
            String user = cred.getUsername();
            String pass = cred.getPassword();
            String driver = envOr("QUANT_KUANGRUI_DRIVER_ID", "DAEB7F56");
            MdsClientImpl c = new MdsClientImpl(cfg.toAbsolutePath().toString());
            c.initCallBack(new MdsCallBack() {
                @Override
                public void onDisConn(MdsClient cl) {
                    // 回调内勿重活：只打标 + 异步 close/重连
                    markDisconnectedAndScheduleCleanup(cl, "disconnected");
                }

                @Override
                public void onMktReq(MdsMktDataRequestRsp rsp) {
                    log.info("[mds] 订阅应答: {}", rsp);
                }

                @Override
                public void onMktStock(MdsMktDataSnapshotHead head, MdsStockSnapshotBody data) {
                    try {
                        feed(head, data);
                    } catch (Exception ex) {
                        log.error("[mds] 回调处理失败: {}", ex.getMessage(), ex);
                    }
                }
            });
            ClientLogonReq logon = new ClientLogonReq();
            logon.setHeartBtInt(30);
            logon.setUsername(user);
            logon.setPassword(pass);
            logon.setClientDriverId(driver);
            Integer enc = resolveEncryptType();
            if (enc != null) {
                logon.setLogonEncryptType(OesLogonEncryptType.valueOf(enc.intValue()));
            }
            ClientLogonRsp rsp = c.start(logon);
            if (rsp == null || !rsp.isSuccess()) {
                try {
                    c.close();
                } catch (Exception ignore) {
                    log.error("MDS 分钟摄入异常", ignore);
                    // ignore
                }
                armBackoff();
                throw new IllegalStateException("MDS 登录失败 rsp=" + rsp);
            }
            client = c;
            disconnected.set(false);
            backoffAttempt.set(0);
            nextReconnectAtMs.set(0L);
            lastError.set(null);
            log.info("[mds] 登录成功 applVerId={}", rsp.getApplVerId());
        }
    }

    private void feed(MdsMktDataSnapshotHead head, MdsStockSnapshotBody data) {
        if (head == null || data == null) {
            return;
        }
        String code = data.getSecurityID();
        if (code == null || code.trim().isEmpty()) {
            code = String.valueOf(head.getInstrId());
            while (code.length() < 6) {
                code = "0" + code;
            }
        }
        aggregator.onSnapshot(
                code.trim(),
                head.getTradeDate(),
                head.getUpdateTime(),
                (long) data.getTradePx(),
                data.getTotalVolumeTraded(),
                data.getTotalValueTraded()
        );
    }

    private void markDisconnectedAndScheduleCleanup(MdsClient dead, String reason) {
        log.error("[mds] 连接中断 reason={}", reason);
        subscribed.set(false);
        disconnected.set(true);
        lastError.set(reason == null ? "disconnected" : reason);
        disconnectCount.incrementAndGet();
        if (!cleanupScheduled.compareAndSet(false, true)) {
            return;
        }
        reconnectExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (clientLock) {
                        if (client != null && (dead == null || client == dead || disconnected.get())) {
                            closeClient();
                        }
                    }
                    scheduleReconnectIfNeeded();
                } finally {
                    cleanupScheduled.set(false);
                }
            }
        });
    }

    private void scheduleReconnectIfNeeded() {
        if (!wantSubscribe.get()) {
            // pull 路径由 ensureClient 懒重连；仅订阅模式主动退避重登
            armBackoff();
            return;
        }
        cancelScheduledReconnect();
        long delay = armBackoff();
        reconnectFuture = reconnectExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                synchronized (clientLock) {
                    if (!wantSubscribe.get()) {
                        return;
                    }
                    try {
                        ensureClient();
                        List<String> codes = lastSubscribeCodes.get();
                        if (codes != null && !codes.isEmpty()) {
                            doSubscribe(codes);
                            subscribed.set(true);
                        }
                        reconnectCount.incrementAndGet();
                        lastError.set(null);
                        log.info("[mds] 断线重连并重订阅成功 codes={}",
                                codes == null ? 0 : codes.size());
                    } catch (Exception e) {
                        lastError.set(e.getMessage());
                        log.error("[mds] 断线重连失败: {}", e.getMessage(), e);
                        scheduleReconnectIfNeeded();
                    }
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
        log.info("[mds] 已调度退避重连 delayMs={}", delay);
    }

    private long armBackoff() {
        int attempt = backoffAttempt.getAndIncrement();
        long delay = RECONNECT_BASE_MS;
        for (int i = 0; i < attempt && delay < RECONNECT_MAX_MS; i++) {
            delay = Math.min(RECONNECT_MAX_MS, delay * 2L);
        }
        nextReconnectAtMs.set(System.currentTimeMillis() + delay);
        return delay;
    }

    private void cancelScheduledReconnect() {
        ScheduledFuture<?> f = reconnectFuture;
        reconnectFuture = null;
        if (f != null) {
            f.cancel(false);
        }
    }

    private static boolean looksLikeDisconnect(Exception e) {
        if (e == null) {
            return false;
        }
        String msg = e.getMessage();
        if (msg == null) {
            msg = e.getClass().getSimpleName();
        } else {
            msg = msg.toLowerCase();
        }
        return msg.contains("disconnect")
                || msg.contains("closed")
                || msg.contains("socket")
                || msg.contains("connection")
                || msg.contains("broken");
    }

    private void closeClient() {
        MdsClientImpl c = client;
        client = null;
        subscribed.set(false);
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                log.error("[mds] close: {}", e.getMessage(), e);
            }
        }
    }

    @PreDestroy
    public void destroy() {
        wantSubscribe.set(false);
        cancelScheduledReconnect();
        stopSubscribe();
        reconnectExecutor.shutdownNow();
    }

    private Path resolveMdsConfig() {
        QuantProperties.Kuangrui k = quantProperties.getKuangrui();
        String dir = k == null || k.getConfigDir() == null || k.getConfigDir().trim().isEmpty()
                ? "config/kuangrui/local"
                : k.getConfigDir().trim();
        String file = k == null || k.getMds() == null || k.getMds().getConfigFile() == null
                ? "mds_api_config.json"
                : k.getMds().getConfigFile().trim();
        return Paths.get(dir, file);
    }

    private Integer resolveEncryptType() {
        QuantProperties.Kuangrui k = quantProperties.getKuangrui();
        if (k == null || k.getMds() == null) {
            return null;
        }
        return k.getMds().getEncryptType();
    }

    private static MdsExchangeId toExch(int id) {
        if (id == KuangruiExchangeIds.SZSE) {
            return MdsExchangeId.MDS_EXCH_SZSE;
        }
        if (id == KuangruiExchangeIds.BSE) {
            return MdsExchangeId.MDS_EXCH_BSE;
        }
        return MdsExchangeId.MDS_EXCH_SSE;
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
