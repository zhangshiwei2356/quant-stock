package com.quant.stock.kuangrui;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认 OES 只读桩：恒不可用；关开关时保持主路径不变。
 * <p>
 * 始终注册；启用宽睿时由 {@code KuangruiOesReadonlyService}（{@code @Primary}）优先注入。
 * </p>
 */
@Service
public class NoopOesReadonlyService implements OesReadonlyService {

    @Override
    public boolean isLive() {
        return false;
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("live", false);
        m.put("impl", "noop");
        m.put("hint", "默认关闭。启用需：mvn -Pkuangrui 打包/运行 + quant.kuangrui.enabled=true"
                + " + quant.kuangrui.oes.enabled=true + local OES 配置与账号；M2 只读、不下单");
        return m;
    }

    @Override
    public boolean ensureReady() {
        return false;
    }

    @Override
    public List<Map<String, Object>> queryCash() {
        return Collections.emptyList();
    }

    @Override
    public List<Map<String, Object>> queryHoldings() {
        return Collections.emptyList();
    }

    @Override
    public List<Map<String, Object>> queryOrders() {
        return Collections.emptyList();
    }

    @Override
    public List<Map<String, Object>> queryTrades() {
        return Collections.emptyList();
    }

    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", false);
        m.put("live", false);
        m.put("message", "OES 未启用或未编译进 classpath（见 status.hint）");
        m.put("cash", Collections.emptyList());
        m.put("holdings", Collections.emptyList());
        m.put("orders", Collections.emptyList());
        m.put("trades", Collections.emptyList());
        return m;
    }

    @Override
    public List<Map<String, Object>> queryStock(String code) {
        return Collections.emptyList();
    }

    @Override
    public Map<String, Object> queryTradingDay() {
        return Collections.emptyMap();
    }

    @Override
    public List<Map<String, Object>> queryCommissionRate() {
        return Collections.emptyList();
    }

    @Override
    public Map<String, Object> queryClientOverview() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", false);
        m.put("live", false);
        return m;
    }

    @Override
    public List<Map<String, Object>> queryInvAcct() {
        return Collections.emptyList();
    }

    @Override
    public List<Map<String, Object>> queryCounterCash(String cashAcctId) {
        return Collections.emptyList();
    }

    @Override
    public Map<String, Object> queryMaxTradableQty(String code, String side, java.math.BigDecimal priceYuan) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", false);
        m.put("live", false);
        return m;
    }

    @Override
    public void stop() {
        // no-op
    }

    @Override
    public Map<String, Object> probeLogon(String username, String password) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", false);
        m.put("message", "OES 未启用或未编译进 classpath，无法验柜");
        m.put("hint", "请以 Maven profile kuangrui 启动，并打开 quant.kuangrui.enabled + oes.enabled");
        return m;
    }
}
