package com.quant.stock.kuangrui;

import com.quant.stock.config.QuantProperties;
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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 宽睿 OES 只读：登录 + {@code sendRptSync} + 查资金/持仓/委托/成交。
 * 仅 {@code -Pkuangrui} 编译；查询经反射适配资料包方法签名差异，不下单（M3）。
 */
@Slf4j
@Service
@Primary
@ConditionalOnClass(OesClientImpl.class)
@ConditionalOnProperty(name = {
        "quant.kuangrui.enabled",
        "quant.kuangrui.oes.enabled"
}, havingValue = "true")
public class KuangruiOesReadonlyService implements OesReadonlyService {

    private final QuantProperties quantProperties;

    private final Object clientLock = new Object();
    private final AtomicBoolean rptSynced = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<String>();
    private final AtomicReference<String> applVerId = new AtomicReference<String>();
    private volatile OesClientImpl client;
    private volatile long lastInMsgSeq;

    public KuangruiOesReadonlyService(QuantProperties quantProperties) {
        this.quantProperties = quantProperties;
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("live", true);
        m.put("impl", "kuangrui-oes");
        m.put("loggedIn", client != null);
        m.put("rptSynced", rptSynced.get());
        m.put("lastInMsgSeq", lastInMsgSeq);
        m.put("applVerId", applVerId.get());
        m.put("lastError", lastError.get());
        m.put("configPath", resolveOesConfig().toString());
        m.put("configExists", Files.isRegularFile(resolveOesConfig()));
        m.put("hasCred", env("QUANT_KUANGRUI_USER") != null && env("QUANT_KUANGRUI_PASSWORD") != null);
        m.put("orderEnabled", quantProperties.getKuangrui() != null
                && quantProperties.getKuangrui().getOes() != null
                && quantProperties.getKuangrui().getOes().isOrderEnabled());
        m.put("hint", "M2 只读对账；报撤需 oes.order-enabled（M3，默认关）");
        return m;
    }

    @Override
    public boolean ensureReady() {
        try {
            ensureClient();
            return client != null && rptSynced.get();
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.warn("[oes] 就绪失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<Map<String, Object>> queryCash() {
        ensureReadyOrThrow();
        List<?> raw = invokeQueryList("queryCashAsset",
                "com.quant360.api.model.oes.OesQryCashAssetFilter");
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : raw) {
            out.add(OesViewMapper.cash(
                    str(invokeGetter(item, "getCashAcctId")),
                    lng(invokeGetter(item, "getCurrentTotalBal")),
                    lng(invokeGetter(item, "getCurrentAvailableBal")),
                    lng(invokeGetter(item, "getCurrentDrawableBal"))
            ));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> queryHoldings() {
        ensureReadyOrThrow();
        List<?> raw = invokeQueryList("queryStkHolding",
                "com.quant360.api.model.oes.OesQryStkHoldingFilter");
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : raw) {
            String code = str(invokeGetter(item, "getSecurityId"));
            if (code == null || code.isEmpty()) {
                code = str(invokeGetter(item, "getSecurityID"));
            }
            out.add(OesViewMapper.holding(
                    code,
                    lng(invokeGetter(item, "getSumHld")),
                    lng(invokeGetter(item, "getSellAvlHld")),
                    lng(invokeGetter(item, "getCostPrice"))
            ));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> queryOrders() {
        ensureReadyOrThrow();
        List<?> raw = invokeQueryList("queryOrder",
                "com.quant360.api.model.oes.OesQryOrdFilter");
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : raw) {
            String code = str(invokeGetter(item, "getSecurityId"));
            if (code == null || code.isEmpty()) {
                code = str(invokeGetter(item, "getSecurityID"));
            }
            Object st = invokeGetter(item, "getOrdStatus");
            int status = toStatusInt(st);
            out.add(OesViewMapper.order(
                    code,
                    lng(invokeGetter(item, "getClOrdId")),
                    (int) lng(invokeGetter(item, "getClSeqNo")),
                    status,
                    lng(invokeGetter(item, "getOrdPrice")),
                    (int) lng(invokeGetter(item, "getOrdQty")),
                    (int) lng(invokeGetter(item, "getCumQty"))
            ));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> queryTrades() {
        ensureReadyOrThrow();
        List<?> raw = invokeQueryList("queryTrade",
                "com.quant360.api.model.oes.OesQryTrdFilter");
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : raw) {
            String code = str(invokeGetter(item, "getSecurityId"));
            if (code == null || code.isEmpty()) {
                code = str(invokeGetter(item, "getSecurityID"));
            }
            out.add(OesViewMapper.trade(
                    code,
                    lng(invokeGetter(item, "getClOrdId")),
                    lng(invokeGetter(item, "getTrdPrice")),
                    (int) lng(invokeGetter(item, "getTrdQty")),
                    lng(invokeGetter(item, "getTrdAmt"))
            ));
        }
        return out;
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
    public void stop() {
        synchronized (clientLock) {
            rptSynced.set(false);
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
            if (client != null && !rptSynced.get()) {
                doRptSync(lastInMsgSeq);
                return;
            }
            Path cfg = resolveOesConfig();
            if (!Files.isRegularFile(cfg)) {
                throw new IllegalStateException("缺少 OES 配置: " + cfg.toAbsolutePath());
            }
            String user = env("QUANT_KUANGRUI_USER");
            String pass = env("QUANT_KUANGRUI_PASSWORD");
            if (user == null || pass == null) {
                throw new IllegalStateException("请设置环境变量 QUANT_KUANGRUI_USER / QUANT_KUANGRUI_PASSWORD");
            }
            String driver = envOr("QUANT_KUANGRUI_DRIVER_ID", "DAEB7F56");
            OesClientImpl c = new OesClientImpl(1, cfg.toAbsolutePath().toString());
            // 回调签名随资料包略有差异；空实现对齐 Demo/LoginProbe。断线时下次 ensureReady 重登。
            c.initCallBack(new OesCallBack() {
            });
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
            log.info("[oes] 登录成功 applVerId={} lastInMsgSeq={}", rsp.getApplVerId(), lastInMsgSeq);
            doRptSync(lastInMsgSeq);
        }
    }

    private void doRptSync(long seq) throws Exception {
        OesClientImpl c = client;
        if (c == null) {
            throw new IllegalStateException("OES 客户端为空");
        }
        // Demo：登录后必须 sendRptSync，否则回报通道易断；签名因版本略有差异
        boolean ok = tryInvokeRptSync(c, seq);
        if (!ok) {
            throw new IllegalStateException("sendRptSync 调用失败（请核对 API 版本 0.19.4）");
        }
        rptSynced.set(true);
        log.info("[oes] sendRptSync 完成 lastInMsgSeq={}", seq);
    }

    private static boolean tryInvokeRptSync(OesClientImpl c, long seq) {
        // sendRptSync(long)
        if (invokeQuiet(c, "sendRptSync", new Class[]{long.class}, new Object[]{Long.valueOf(seq)})) {
            return true;
        }
        if (invokeQuiet(c, "sendRptSync", new Class[]{Long.class}, new Object[]{Long.valueOf(seq)})) {
            return true;
        }
        // initRptSync + sendRptSync()
        if (invokeQuiet(c, "initRptSync", new Class[]{long.class}, new Object[]{Long.valueOf(seq)})
                || invokeQuiet(c, "initRptSync", new Class[]{Long.class}, new Object[]{Long.valueOf(seq)})) {
            if (invokeQuiet(c, "sendRptSync", new Class[]{}, new Object[]{})) {
                return true;
            }
        }
        // sendReportSynchronization(long)
        if (invokeQuiet(c, "sendReportSynchronization", new Class[]{long.class}, new Object[]{Long.valueOf(seq)})) {
            return true;
        }
        // 无参 sendRptSync（部分 Demo 仅通知开始推送）
        return invokeQuiet(c, "sendRptSync", new Class[]{}, new Object[]{});
    }

    private List<?> invokeQueryList(String methodName, String filterClassName) {
        OesClientImpl c = client;
        if (c == null) {
            return Collections.emptyList();
        }
        Object filter = newInstance(filterClassName);
        // List queryXxx(Filter)
        Object list = invokeReturning(c, methodName, filter);
        if (list instanceof List) {
            return (List<?>) list;
        }
        if (list instanceof Collection) {
            return new ArrayList<Object>((Collection<?>) list);
        }
        // int queryXxx(Filter, callback) — 少见；已有 List 即可
        if (filter != null) {
            Object list2 = invokeReturning(c, methodName, new Object[]{null});
            if (list2 instanceof List) {
                return (List<?>) list2;
            }
        }
        log.warn("[oes] {} 未返回 List，实际={}", methodName, list == null ? "null" : list.getClass().getName());
        return Collections.emptyList();
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
                log.debug("[oes] invoke {} 失败: {}", name, e.getMessage());
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

    private static boolean invokeQuiet(Object target, String name, Class<?>[] types, Object[] args) {
        try {
            Method m = target.getClass().getMethod(name, types);
            m.invoke(target, args);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (Exception e) {
            log.debug("[oes] {} 调用异常: {}", name, e.getMessage());
            return false;
        }
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
