package com.quant.stock.kuangrui;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.risk.LimitBoardHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 宽睿 M4 静态/费率门面：合并 MDS/OES 查询结果，供开仓过滤、佣金、交易日可选覆盖。
 * <p>
 * 仅当 {@code quant.kuangrui.enabled}+{@code static-enabled} 时 {@link #isApplyEnabled()} 为 true；
 * 否则业务一律回退本地启发式（量≤0 停牌、规则涨跌停、配置 feeRate、内置节假日）。
 * </p>
 */
@Slf4j
@Service
public class KuangruiStaticInfoService {

    private static final long STOCK_TTL_MS = 60_000L;
    private static final long META_TTL_MS = 300_000L;

    private final QuantProperties quantProperties;
    private final OesReadonlyService oesReadonlyService;
    private final MdsMinuteIngestService mdsMinuteIngestService;

    private final ConcurrentHashMap<String, CacheEntry> stockCache = new ConcurrentHashMap<String, CacheEntry>();
    private final AtomicReference<MetaCache> metaCache = new AtomicReference<MetaCache>();

    public KuangruiStaticInfoService(QuantProperties quantProperties,
                                     OesReadonlyService oesReadonlyService,
                                     MdsMinuteIngestService mdsMinuteIngestService) {
        this.quantProperties = quantProperties;
        this.oesReadonlyService = oesReadonlyService;
        this.mdsMinuteIngestService = mdsMinuteIngestService;
    }

    /** 业务是否应用宽睿静态/费率（可开关回退）。 */
    public boolean isApplyEnabled() {
        QuantProperties.Kuangrui k = quantProperties.getKuangrui();
        return k != null && k.isEnabled() && k.isStaticEnabled();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        QuantProperties.Kuangrui k = quantProperties.getKuangrui();
        m.put("applyEnabled", isApplyEnabled());
        m.put("staticEnabled", k != null && k.isStaticEnabled());
        m.put("kuangruiEnabled", k != null && k.isEnabled());
        m.put("oesLive", oesReadonlyService != null && oesReadonlyService.isLive());
        m.put("mdsLive", mdsMinuteIngestService != null && mdsMinuteIngestService.isLive());
        m.put("stockCacheSize", stockCache.size());
        MetaCache meta = metaCache.get();
        m.put("hasTradingDayCache", meta != null && meta.tradingDay != null);
        m.put("hasCommissionCache", meta != null && meta.commissionRate != null);
        m.put("hint", isApplyEnabled()
                ? "M4 已开：涨跌停/停牌/股本/交易日/佣金优先宽睿，失败回退本地"
                : "M4 默认关；设 quant.kuangrui.enabled+static-enabled=true 且 MDS/OES 就绪后生效");
        return m;
    }

    /** 刷新并返回合并后的证券静态（优先 MDS，OES 补齐）。 */
    public Map<String, Object> stockStatic(String code) {
        String norm = OesViewMapper.normalizeCode(code);
        if (!StringUtils.hasText(norm)) {
            return java.util.Collections.emptyMap();
        }
        CacheEntry e = stockCache.get(norm);
        long now = System.currentTimeMillis();
        if (e != null && now - e.atMs < STOCK_TTL_MS) {
            return e.view;
        }
        Map<String, Object> view = fetchStock(norm);
        if (view != null && !view.isEmpty()) {
            stockCache.put(norm, new CacheEntry(view, now));
        }
        return view == null ? java.util.Collections.<String, Object>emptyMap() : view;
    }

    /** 柜台/行情源停牌；无数据返回 null（调用方回退量≤0）。 */
    public Boolean isSuspended(String code) {
        if (!isApplyEnabled()) {
            return null;
        }
        Map<String, Object> v = stockStatic(code);
        if (v == null || v.isEmpty() || !v.containsKey("suspended")) {
            return null;
        }
        Object s = v.get("suspended");
        return s instanceof Boolean ? (Boolean) s : null;
    }

    /** 流通股本（亿股）；无数据 null。 */
    public BigDecimal floatSharesYi(String code) {
        if (!isApplyEnabled()) {
            return null;
        }
        Map<String, Object> v = stockStatic(code);
        if (v == null) {
            return null;
        }
        Object yi = v.get("floatSharesYi");
        return yi instanceof BigDecimal ? (BigDecimal) yi : null;
    }

    public BigDecimal upperLimit(String code) {
        return decimalField(code, "upperLimit");
    }

    public BigDecimal lowerLimit(String code) {
        return decimalField(code, "lowerLimit");
    }

    /**
     * 若有柜台涨跌停价，用其判定封板/触及；无数据返回 null（调用方回退规则估算）。
     */
    public Boolean isLimitBoard(String code, BarDTO cur, boolean up) {
        if (!isApplyEnabled() || cur == null) {
            return null;
        }
        BigDecimal limit = up ? upperLimit(code) : lowerLimit(code);
        if (limit == null) {
            return null;
        }
        if (LimitBoardHelper.isLockedAt(cur, limit)) {
            return Boolean.TRUE;
        }
        BigDecimal close = cur.getClose();
        if (close == null) {
            return Boolean.FALSE;
        }
        BigDecimal tick = new BigDecimal("0.01");
        if (up) {
            return Boolean.valueOf(close.subtract(limit).abs().compareTo(tick) <= 0
                    || close.compareTo(limit) >= 0);
        }
        return Boolean.valueOf(close.subtract(limit).abs().compareTo(tick) <= 0
                || close.compareTo(limit) <= 0);
    }

    /** OES 当前交易日；无数据 null。 */
    public LocalDate exchangeTradingDay() {
        if (!isApplyEnabled()) {
            return null;
        }
        ensureMeta();
        MetaCache meta = metaCache.get();
        return meta == null ? null : meta.tradingDay;
    }

    /** OES 佣金费率（小数）；无数据 null → 回退 quant.feeRate。 */
    public BigDecimal commissionRate() {
        if (!isApplyEnabled()) {
            return null;
        }
        ensureMeta();
        MetaCache meta = metaCache.get();
        return meta == null ? null : meta.commissionRate;
    }

    /** MDS 时段是否开市；无数据 null。 */
    public Boolean sessionOpen() {
        if (!isApplyEnabled()) {
            return null;
        }
        ensureMeta();
        MetaCache meta = metaCache.get();
        return meta == null ? null : meta.sessionOpen;
    }

    public Map<String, Object> tradingDayView() {
        if (oesReadonlyService == null || !oesReadonlyService.isLive()) {
            return java.util.Collections.emptyMap();
        }
        try {
            if (!oesReadonlyService.ensureReady()) {
                return java.util.Collections.emptyMap();
            }
            return oesReadonlyService.queryTradingDay();
        } catch (Exception e) {
            log.error("[m4] tradingDay: {}", e.getMessage(), e);
            return java.util.Collections.emptyMap();
        }
    }

    public List<Map<String, Object>> commissionRateView() {
        if (oesReadonlyService == null || !oesReadonlyService.isLive()) {
            return java.util.Collections.emptyList();
        }
        try {
            if (!oesReadonlyService.ensureReady()) {
                return java.util.Collections.emptyList();
            }
            return oesReadonlyService.queryCommissionRate();
        } catch (Exception e) {
            log.error("[m4] commission: {}", e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }

    public List<Map<String, Object>> mdsStockStatic(String code) {
        if (mdsMinuteIngestService == null || !mdsMinuteIngestService.isLive()) {
            return java.util.Collections.emptyList();
        }
        try {
            return mdsMinuteIngestService.queryStockStatic(code);
        } catch (Exception e) {
            log.error("[m4] mds static: {}", e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }

    public List<Map<String, Object>> mdsSecurityStatus(String code) {
        if (mdsMinuteIngestService == null || !mdsMinuteIngestService.isLive()) {
            return java.util.Collections.emptyList();
        }
        try {
            return mdsMinuteIngestService.querySecurityStatus(code);
        } catch (Exception e) {
            log.error("[m4] mds status: {}", e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }

    public List<Map<String, Object>> mdsSessionStatus() {
        if (mdsMinuteIngestService == null || !mdsMinuteIngestService.isLive()) {
            return java.util.Collections.emptyList();
        }
        try {
            return mdsMinuteIngestService.queryTrdSessionStatus();
        } catch (Exception e) {
            log.error("[m4] mds session: {}", e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }

    private BigDecimal decimalField(String code, String key) {
        if (!isApplyEnabled()) {
            return null;
        }
        Map<String, Object> v = stockStatic(code);
        if (v == null) {
            return null;
        }
        Object o = v.get(key);
        return o instanceof BigDecimal ? (BigDecimal) o : null;
    }

    private Map<String, Object> fetchStock(String norm) {
        Map<String, Object> merged = new java.util.LinkedHashMap<String, Object>();
        // MDS 优先
        List<Map<String, Object>> mdsList = mdsStockStatic(norm);
        if (mdsList != null && !mdsList.isEmpty()) {
            merged.putAll(mdsList.get(0));
        }
        List<Map<String, Object>> st = mdsSecurityStatus(norm);
        if (st != null && !st.isEmpty()) {
            Map<String, Object> s0 = st.get(0);
            if (s0.get("suspended") != null) {
                merged.put("suspended", s0.get("suspended"));
            }
            if (s0.get("suspendFlag") != null) {
                merged.put("suspendFlag", s0.get("suspendFlag"));
            }
            if (s0.get("securityStatus") != null) {
                merged.put("securityStatus", s0.get("securityStatus"));
            }
        }
        // OES 补齐
        if (oesReadonlyService != null && oesReadonlyService.isLive()) {
            try {
                if (oesReadonlyService.ensureReady()) {
                    List<Map<String, Object>> oesList = oesReadonlyService.queryStock(norm);
                    if (oesList != null && !oesList.isEmpty()) {
                        Map<String, Object> o0 = oesList.get(0);
                        for (Map.Entry<String, Object> e : o0.entrySet()) {
                            if (!merged.containsKey(e.getKey()) || merged.get(e.getKey()) == null) {
                                merged.put(e.getKey(), e.getValue());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[m4] oes stock: {}", e.getMessage(), e);
            }
        }
        if (!merged.containsKey("code")) {
            merged.put("code", norm);
        }
        return merged;
    }

    private void ensureMeta() {
        MetaCache cur = metaCache.get();
        long now = System.currentTimeMillis();
        if (cur != null && now - cur.atMs < META_TTL_MS) {
            return;
        }
        LocalDate day = null;
        BigDecimal rate = null;
        Boolean open = null;
        Map<String, Object> td = tradingDayView();
        if (td != null) {
            Object s = td.get("tradingDay");
            if (s instanceof String && StringUtils.hasText((String) s)) {
                try {
                    day = LocalDate.parse((String) s);
                } catch (Exception ignore) {
                    log.error("宽睿静态信息异常", ignore);
                    // ignore
                }
            }
        }
        List<Map<String, Object>> rates = commissionRateView();
        if (rates != null) {
            for (Map<String, Object> r : rates) {
                Object fr = r.get("feeRate");
                if (fr instanceof BigDecimal && ((BigDecimal) fr).compareTo(BigDecimal.ZERO) > 0) {
                    rate = (BigDecimal) fr;
                    break;
                }
            }
        }
        List<Map<String, Object>> sessions = mdsSessionStatus();
        if (sessions != null) {
            for (Map<String, Object> s : sessions) {
                Object o = s.get("open");
                if (o instanceof Boolean) {
                    open = (Boolean) o;
                    if (Boolean.TRUE.equals(open)) {
                        break;
                    }
                }
            }
        }
        metaCache.set(new MetaCache(day, rate, open, now));
    }

    private static final class CacheEntry {
        final Map<String, Object> view;
        final long atMs;

        CacheEntry(Map<String, Object> view, long atMs) {
            this.view = view;
            this.atMs = atMs;
        }
    }

    private static final class MetaCache {
        final LocalDate tradingDay;
        final BigDecimal commissionRate;
        final Boolean sessionOpen;
        final long atMs;

        MetaCache(LocalDate tradingDay, BigDecimal commissionRate, Boolean sessionOpen, long atMs) {
            this.tradingDay = tradingDay;
            this.commissionRate = commissionRate;
            this.sessionOpen = sessionOpen;
            this.atMs = atMs;
        }
    }
}
