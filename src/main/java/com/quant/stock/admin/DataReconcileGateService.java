package com.quant.stock.admin;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.BarPeriod;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.risk.AlertSeverity;
import com.quant.stock.risk.RiskAlertService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 行情自洽闸（原 P0-107 多源对账）：在仅 {@code market_1min} 真相源下，
 * 检查 1 分钟覆盖/滞后/稀疏日/OHLC 合法性；外部双源仍 {@code UNAVAILABLE}。
 */
@Service
public class DataReconcileGateService {

    /** 单交易日 1 分钟根数低于该值视为稀疏（完整日约 240） */
    static final int MIN_BARS_PER_DAY = 120;
    /** OHLC 抽检最多根数 */
    private static final int OHLC_SAMPLE_LIMIT = 800;

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

    /** 是否因自洽失败阻断新开 */
    public boolean blockNewOpen() {
        return props.isDataReconcileGateEnabled() && gateOpenBlocked.get();
    }

    /** 返回最近一次自洽检查摘要（含闸状态） */
    public Map<String, Object> lastReport() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.putAll(lastReport);
        m.put("gateEnabled", props.isDataReconcileGateEnabled());
        m.put("blockNewOpen", blockNewOpen());
        m.put("maxCloseDiffPct", props.getDataReconcileMaxCloseDiffPct());
        m.put("lastRunAt", lastRunAt == null ? null : lastRunAt.toString());
        m.put("primarySource", lastReport.getOrDefault("primarySource", "MARKET_1MIN"));
        m.put("secondarySource", lastReport.getOrDefault("secondarySource", "SELF_CONSISTENCY"));
        m.put("externalVendorSource", "UNAVAILABLE");
        m.put("hint", lastReport.getOrDefault("hint",
                "检查分钟K是否为空、过旧、稀疏或 OHLC 不合法；默认只告警不禁止开仓；外部多源对账仍未接入"));
        return m;
    }

    /**
     * 对给定代码列表（空则经桥接取目标池/universe）执行 1 分钟自洽检查。
     */
    public Map<String, Object> reconcile(List<String> codes) {
        List<String> universe = codes;
        if (universe == null || universe.isEmpty()) {
            TradePoolServiceBridge bridge = poolBridge.getIfAvailable();
            universe = bridge == null ? new ArrayList<String>() : bridge.activeOrUniverseCodes();
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
            Map<String, Object> one = checkOne(code, sampleDays);
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
                    "1分钟自洽失败 " + divergeCodes + " 只，阻断新开");
        } else if (divergeCodes > 0) {
            riskAlertService.emit(LocalDate.now(), null, "DATA_RECONCILE_WARN", AlertSeverity.WARN,
                    BigDecimal.valueOf(divergeCodes),
                    "1分钟自洽失败 " + divergeCodes + " 只（未阻断）");
        }

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("asOf", lastRunAt.toString());
        m.put("checked", checked);
        m.put("divergeCodeCount", divergeCodes);
        m.put("blockNewOpen", block);
        m.put("sampleDays", sampleDays);
        m.put("minBarsPerDay", MIN_BARS_PER_DAY);
        m.put("maxCloseDiffPct", props.getDataReconcileMaxCloseDiffPct());
        m.put("maxCloseDiffPctUsed", false);
        m.put("divergences", divergences);
        m.put("primarySource", "MARKET_1MIN");
        m.put("secondarySource", "SELF_CONSISTENCY");
        m.put("hint", "检查分钟K（market_1min）是否为空、过旧、当日根数过少或 OHLC 不合法；"
                + "默认只告警不禁止开仓（data-reconcile-block-on-diverge=false）；外部多源对账仍未接入");
        lastReport = m;
        return lastReport();
    }

    private Map<String, Object> checkOne(String code, int sampleDays) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("code", code);
        row.put("diverged", false);
        List<String> issues = new ArrayList<String>();
        try {
            List<BarDTO> ones = marketDataService.getKline(code, BarPeriod.MIN_1, null, null);
            if (ones == null || ones.isEmpty()) {
                issues.add("1分钟为空");
                row.put("diverged", true);
                row.put("issues", issues);
                return row;
            }
            row.put("oneMinCount", ones.size());
            BarDTO last = ones.get(ones.size() - 1);
            LocalDate lastDay = last.getBarBegin() == null ? null : last.getBarBegin().toLocalDate();
            row.put("maxOneMin", last.getBarBegin() == null ? null : last.getBarBegin().toString());
            if (lastDay != null) {
                long lagDays = ChronoUnit.DAYS.between(lastDay, LocalDate.now());
                row.put("lagDays", lagDays);
                if (lagDays > sampleDays) {
                    issues.add("覆盖滞后" + lagDays + "天(阈=" + sampleDays + ")");
                }
            }

            Map<LocalDate, Integer> byDay = new TreeMap<LocalDate, Integer>();
            for (BarDTO b : ones) {
                if (b == null || b.getBarBegin() == null) {
                    continue;
                }
                LocalDate d = b.getBarBegin().toLocalDate();
                Integer c = byDay.get(d);
                byDay.put(d, c == null ? 1 : c.intValue() + 1);
            }
            List<LocalDate> days = new ArrayList<LocalDate>(byDay.keySet());
            int from = Math.max(0, days.size() - sampleDays);
            List<Map<String, Object>> sparse = new ArrayList<Map<String, Object>>();
            for (int i = from; i < days.size(); i++) {
                LocalDate d = days.get(i);
                int n = byDay.get(d);
                if (n < MIN_BARS_PER_DAY) {
                    Map<String, Object> s = new LinkedHashMap<String, Object>();
                    s.put("day", d.toString());
                    s.put("bars", n);
                    sparse.add(s);
                }
            }
            row.put("sparseDays", sparse);
            if (!sparse.isEmpty()) {
                issues.add("稀疏日" + sparse.size() + "个(日根数<" + MIN_BARS_PER_DAY + ")");
            }

            int ohlcBad = 0;
            int sampled = 0;
            int start = Math.max(0, ones.size() - OHLC_SAMPLE_LIMIT);
            for (int i = start; i < ones.size(); i++) {
                BarDTO b = ones.get(i);
                sampled++;
                if (!ohlcOk(b)) {
                    ohlcBad++;
                }
            }
            row.put("ohlcSampled", sampled);
            row.put("ohlcBad", ohlcBad);
            if (ohlcBad > 0) {
                issues.add("OHLC非法" + ohlcBad + "根");
            }

            boolean diverged = !issues.isEmpty();
            row.put("diverged", diverged);
            row.put("issues", issues);
        } catch (Exception e) {
            row.put("diverged", true);
            row.put("skip", e.getMessage());
            issues.add("校验异常: " + e.getMessage());
            row.put("issues", issues);
        }
        return row;
    }

    static boolean ohlcOk(BarDTO b) {
        if (b == null || b.getOpen() == null || b.getHigh() == null
                || b.getLow() == null || b.getClose() == null) {
            return false;
        }
        if (b.getOpen().compareTo(BigDecimal.ZERO) <= 0
                || b.getHigh().compareTo(BigDecimal.ZERO) <= 0
                || b.getLow().compareTo(BigDecimal.ZERO) <= 0
                || b.getClose().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (b.getHigh().compareTo(b.getLow()) < 0) {
            return false;
        }
        if (b.getHigh().compareTo(b.getOpen().max(b.getClose())) < 0) {
            return false;
        }
        if (b.getLow().compareTo(b.getOpen().min(b.getClose())) > 0) {
            return false;
        }
        return true;
    }

    /**
     * 避免 admin→pool 循环依赖的窄桥；无池时返回空列表。
     */
    public interface TradePoolServiceBridge {
        /** 对账扫描用的股票代码列表 */
        List<String> activeOrUniverseCodes();
    }
}
