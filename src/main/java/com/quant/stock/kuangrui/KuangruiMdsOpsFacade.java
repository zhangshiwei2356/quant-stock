package com.quant.stock.kuangrui;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.pool.TradePoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维侧 MDS 辅助：解析订阅标的、转发门面调用（主工程可编译，不依赖 quant360）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class KuangruiMdsOpsFacade {

    private final MdsMinuteIngestService mdsMinuteIngestService;
    private final QuantProperties quantProperties;
    private final TradePoolService tradePoolService;
    private final KuangruiStaticInfoService staticInfoService;

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>(mdsMinuteIngestService.status());
        QuantProperties.Kuangrui k = quantProperties.getKuangrui();
        m.put("quantKuangruiEnabled", k != null && k.isEnabled());
        m.put("quantMdsEnabled", k != null && k.getMds() != null && k.getMds().isEnabled());
        m.put("staticEnabled", k != null && k.isStaticEnabled());
        m.put("configDir", k == null ? null : k.getConfigDir());
        return m;
    }

    public Map<String, Object> pull() {
        List<String> codes = resolveCodes();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("live", mdsMinuteIngestService.isLive());
        if (!mdsMinuteIngestService.isLive()) {
            m.put("ok", false);
            m.put("message", "MDS 未启用或未编译进 classpath（见 status.hint）");
            m.put("codes", codes.size());
            return m;
        }
        int n = mdsMinuteIngestService.pullAndPersist(codes);
        m.put("ok", true);
        m.put("codes", codes.size());
        m.put("upserted", n);
        return m;
    }

    public Map<String, Object> startSubscribe() {
        List<String> codes = resolveCodes();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        boolean ok = mdsMinuteIngestService.startSubscribe(codes);
        m.put("ok", ok);
        m.put("codes", codes.size());
        m.putAll(mdsMinuteIngestService.status());
        return m;
    }

    public Map<String, Object> stopSubscribe() {
        mdsMinuteIngestService.stopSubscribe();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.putAll(mdsMinuteIngestService.status());
        return m;
    }

    public Map<String, Object> flush() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        int n = mdsMinuteIngestService.flushBuckets();
        m.put("ok", true);
        m.put("upserted", n);
        return m;
    }

    /** M4：证券静态信息。 */
    public Map<String, Object> stockStatic(String code) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("live", mdsMinuteIngestService.isLive());
        if (!mdsMinuteIngestService.isLive()) {
            m.put("ok", false);
            m.put("message", "MDS 未启用或未编译进 classpath（见 status.hint）");
            m.put("stockStatic", java.util.Collections.emptyList());
            return m;
        }
        try {
            java.util.List<Map<String, Object>> list = mdsMinuteIngestService.queryStockStatic(code);
            m.put("ok", true);
            m.put("stockStatic", list);
            m.put("count", list.size());
            return m;
        } catch (Exception e) {
            log.warn("[mds-ops] stockStatic 失败: {}", e.getMessage());
            m.put("ok", false);
            m.put("message", e.getMessage());
            m.put("stockStatic", java.util.Collections.emptyList());
            return m;
        }
    }

    /** M4：证券状态。 */
    public Map<String, Object> securityStatus(String code) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("live", mdsMinuteIngestService.isLive());
        if (!mdsMinuteIngestService.isLive()) {
            m.put("ok", false);
            m.put("message", "MDS 未启用或未编译进 classpath（见 status.hint）");
            m.put("securityStatus", java.util.Collections.emptyList());
            return m;
        }
        try {
            java.util.List<Map<String, Object>> list = mdsMinuteIngestService.querySecurityStatus(code);
            m.put("ok", true);
            m.put("securityStatus", list);
            m.put("count", list.size());
            return m;
        } catch (Exception e) {
            log.warn("[mds-ops] securityStatus 失败: {}", e.getMessage());
            m.put("ok", false);
            m.put("message", e.getMessage());
            m.put("securityStatus", java.util.Collections.emptyList());
            return m;
        }
    }

    /** M4：交易时段。 */
    public Map<String, Object> sessionStatus() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("live", mdsMinuteIngestService.isLive());
        if (!mdsMinuteIngestService.isLive()) {
            m.put("ok", false);
            m.put("message", "MDS 未启用或未编译进 classpath（见 status.hint）");
            m.put("sessionStatus", java.util.Collections.emptyList());
            return m;
        }
        try {
            java.util.List<Map<String, Object>> list = mdsMinuteIngestService.queryTrdSessionStatus();
            m.put("ok", true);
            m.put("sessionStatus", list);
            m.put("count", list.size());
            return m;
        } catch (Exception e) {
            log.warn("[mds-ops] sessionStatus 失败: {}", e.getMessage());
            m.put("ok", false);
            m.put("message", e.getMessage());
            m.put("sessionStatus", java.util.Collections.emptyList());
            return m;
        }
    }

    /** M4：合并静态视图（MDS+OES）。 */
    public Map<String, Object> mergedStock(String code) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.putAll(staticInfoService.status());
        m.put("stock", staticInfoService.stockStatic(code));
        m.put("ok", true);
        return m;
    }

    public List<String> resolveCodes() {
        List<String> codes = new ArrayList<String>();
        QuantProperties.Kuangrui k = quantProperties.getKuangrui();
        if (k != null && k.getMds() != null && k.getMds().getSubscribeCodes() != null
                && !k.getMds().getSubscribeCodes().trim().isEmpty()) {
            for (String s : k.getMds().getSubscribeCodes().split(",")) {
                if (s != null && !s.trim().isEmpty()) {
                    codes.add(s.trim());
                }
            }
            return codes;
        }
        try {
            for (Map<String, String> u : tradePoolService.listUniverse()) {
                if (u.get("code") != null) {
                    codes.add(u.get("code"));
                }
            }
        } catch (Exception e) {
            log.debug("[mds-ops] universe 读取失败: {}", e.getMessage());
        }
        if (codes.isEmpty()) {
            codes.addAll(quantProperties.stockCodeList());
        }
        return codes;
    }
}
