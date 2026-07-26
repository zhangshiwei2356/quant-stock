package com.quant.stock.admin;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.BarAggregateUtil;
import com.quant.stock.market.BarPeriod;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.risk.AlertSeverity;
import com.quant.stock.risk.RiskAlertService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多源对账闸（P0-107）：主源日线 vs 分钟聚合日线 OHLC 分歧。
 * 超阈可阻断新开；不改金叉。外部券商源仍待 API。
 */
@Service
public class DataReconcileGateService {

    private final QuantProperties props;
    private final MarketDataService marketDataService;
    private final RiskAlertService riskAlertService;
    private final ObjectProvider<TradePoolServiceBridge> poolBridge;

    private final AtomicBoolean gateOpenBlocked = new AtomicBoolean(false);
    private volatile LocalDateTime lastRunAt;
    private volatile Map<String, Object> lastReport = new LinkedHashMap<String, Object>();

    public DataReconcileGateService(QuantProperties props,
                                    MarketDataService marketDataService,
                                    RiskAlertService riskAlertService,
                                    ObjectProvider<TradePoolServiceBridge> poolBridge) {
        this.props = props;
        this.marketDataService = marketDataService;
        this.riskAlertService = riskAlertService;
        this.poolBridge = poolBridge;
    }

    /** 是否因对账分歧阻断新开 */
    public boolean blockNewOpen() {
        return props.isDataReconcileGateEnabled() && gateOpenBlocked.get();
    }

    /** 返回最近一次对账结果摘要（含闸状态） */
    public Map<String, Object> lastReport() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.putAll(lastReport);
        m.put("gateEnabled", props.isDataReconcileGateEnabled());
        m.put("blockNewOpen", blockNewOpen());
        m.put("maxCloseDiffPct", props.getDataReconcileMaxCloseDiffPct());
        m.put("lastRunAt", lastRunAt == null ? null : lastRunAt.toString());
        m.put("primarySource", lastReport.getOrDefault("primarySource", "DAY_KLINE"));
        m.put("secondarySource", lastReport.getOrDefault("secondarySource", "MINUTE_AGG_DAY"));
        m.put("externalVendorSource", "UNAVAILABLE");
        m.put("hint", "主源=日线表/日K；校验源=分钟聚合日线；外部 vendor 对账仍待 API");
        return m;
    }

    /**
     * 对给定代码列表（空则经桥接取目标池/universe）执行主源日线 vs 分钟聚合日线对账。
     */
    public Map<String, Object> reconcile(List<String> codes) {
        List<String> universe = codes;
        if (universe == null || universe.isEmpty()) {
            TradePoolServiceBridge bridge = poolBridge.getIfAvailable();
            universe = bridge == null ? new ArrayList<String>() : bridge.activeOrUniverseCodes();
        }
        BigDecimal maxDiff = props.getDataReconcileMaxCloseDiffPct();
        if (maxDiff == null || maxDiff.compareTo(BigDecimal.ZERO) <= 0) {
            maxDiff = new BigDecimal("0.02");
        }
        int sampleDays = Math.max(3, props.getDataReconcileSampleDays());
        List<Map<String, Object>> divergences = new ArrayList<Map<String, Object>>();
        int checked = 0;
        int divergeCodes = 0;

        for (String code : universe) {
            if (code == null || code.isEmpty()) {
                continue;
            }
            checked++;
            Map<String, Object> one = reconcileOne(code, sampleDays, maxDiff);
            if (Boolean.TRUE.equals(one.get("diverged"))) {
                divergeCodes++;
                divergences.add(one);
            }
        }

        boolean block = props.isDataReconcileGateEnabled()
                && divergeCodes > 0
                && props.isDataReconcileBlockOnDiverge();
        gateOpenBlocked.set(block);
        lastRunAt = LocalDateTime.now();

        if (block) {
            riskAlertService.emit(LocalDate.now(), null, "DATA_RECONCILE_GATE", AlertSeverity.CRITICAL,
                    BigDecimal.valueOf(divergeCodes),
                    "主源/分钟聚合分歧 " + divergeCodes + " 只，阻断新开");
        } else if (divergeCodes > 0) {
            riskAlertService.emit(LocalDate.now(), null, "DATA_RECONCILE_WARN", AlertSeverity.WARN,
                    BigDecimal.valueOf(divergeCodes),
                    "主源/分钟聚合分歧 " + divergeCodes + " 只（未阻断）");
        }

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("asOf", lastRunAt.toString());
        m.put("checked", checked);
        m.put("divergeCodeCount", divergeCodes);
        m.put("blockNewOpen", block);
        m.put("maxCloseDiffPct", maxDiff);
        m.put("sampleDays", sampleDays);
        m.put("divergences", divergences);
        m.put("primarySource", "DAY_KLINE");
        m.put("secondarySource", "MINUTE_AGG_DAY");
        lastReport = m;
        return lastReport();
    }

    private Map<String, Object> reconcileOne(String code, int sampleDays, BigDecimal maxDiff) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("code", code);
        row.put("diverged", false);
        try {
            List<BarDTO> daily = marketDataService.getKline(code, BarPeriod.DAY, null, null);
            List<BarDTO> minutes = marketDataService.getKline(code, BarPeriod.MIN_5, null, null);
            if (daily == null || daily.size() < sampleDays || minutes == null || minutes.isEmpty()) {
                row.put("skip", "样本不足");
                return row;
            }
            List<BarDTO> agg = BarAggregateUtil.aggregate(minutes, BarAggregateUtil.Period.DAY);
            if (agg == null || agg.isEmpty()) {
                row.put("skip", "分钟无法聚合");
                return row;
            }
            Map<LocalDate, BarDTO> byDay = new HashMap<LocalDate, BarDTO>();
            for (BarDTO b : agg) {
                if (b != null && b.getBarBegin() != null) {
                    byDay.put(b.getBarBegin().toLocalDate(), b);
                }
            }
            int compared = 0;
            BigDecimal maxAbs = BigDecimal.ZERO;
            List<Map<String, Object>> samples = new ArrayList<Map<String, Object>>();
            for (int i = daily.size() - 1; i >= 0 && compared < sampleDays; i--) {
                BarDTO d = daily.get(i);
                if (d == null || d.getBarBegin() == null || d.getClose() == null) {
                    continue;
                }
                LocalDate day = d.getBarBegin().toLocalDate();
                BarDTO a = byDay.get(day);
                if (a == null || a.getClose() == null || d.getClose().compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal diff = a.getClose().subtract(d.getClose())
                        .divide(d.getClose(), 6, RoundingMode.HALF_UP).abs();
                compared++;
                if (diff.compareTo(maxAbs) > 0) {
                    maxAbs = diff;
                }
                if (diff.compareTo(maxDiff) > 0) {
                    Map<String, Object> s = new LinkedHashMap<String, Object>();
                    s.put("day", day.toString());
                    s.put("primaryClose", d.getClose());
                    s.put("secondaryClose", a.getClose());
                    s.put("absDiffPct", diff);
                    samples.add(s);
                }
            }
            row.put("comparedDays", compared);
            row.put("maxAbsDiffPct", maxAbs);
            row.put("samples", samples);
            boolean diverged = !samples.isEmpty();
            row.put("diverged", diverged);
        } catch (Exception e) {
            row.put("skip", e.getMessage());
        }
        return row;
    }

    /**
     * 避免 admin→pool 循环依赖的窄桥；无池时返回空列表。
     */
    public interface TradePoolServiceBridge {
        /** 对账扫描用的股票代码列表 */
        List<String> activeOrUniverseCodes();
    }
}
