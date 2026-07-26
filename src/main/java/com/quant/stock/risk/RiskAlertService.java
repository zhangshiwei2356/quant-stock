package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 软/硬预算告警 + 分级冷却（P0-97）。
 * 硬事件（熔断/退役）优先落库；同类告警在冷却窗内去重。
 */
@Service
public class RiskAlertService {

    private static final int RING_MAX = 200;

    private final QuantProperties props;
    private final ObjectProvider<RiskControlLogService> riskLogProvider;

    /** key → 上次发出时间 */
    private final ConcurrentHashMap<String, LocalDateTime> lastEmit = new ConcurrentHashMap<String, LocalDateTime>();
    private final CopyOnWriteArrayList<Map<String, Object>> recent = new CopyOnWriteArrayList<Map<String, Object>>();

    public RiskAlertService(QuantProperties props, ObjectProvider<RiskControlLogService> riskLogProvider) {
        this.props = props;
        this.riskLogProvider = riskLogProvider;
    }

    /**
     * @return true 表示本次实际发出（未命中冷却）
     */
    public boolean emit(LocalDate day, String symbol, String ruleType, AlertSeverity severity,
                        BigDecimal triggerValue, String actionTaken) {
        AlertSeverity sev = severity == null ? AlertSeverity.INFO : severity;
        String key = (ruleType == null ? "UNKNOWN" : ruleType) + "|"
                + (symbol == null || symbol.isEmpty() ? "*" : symbol) + "|"
                + (day == null ? LocalDate.now() : day);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime prev = lastEmit.get(key);
        if (prev != null) {
            long coolMin = cooldownMinutes(sev);
            if (coolMin > 0 && prev.plusMinutes(coolMin).isAfter(now)) {
                return false;
            }
        }
        lastEmit.put(key, now);

        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("at", now.toString());
        row.put("day", day == null ? null : day.toString());
        row.put("symbol", symbol);
        row.put("ruleType", ruleType);
        row.put("severity", sev.name());
        row.put("triggerValue", triggerValue);
        row.put("action", actionTaken);
        recent.add(0, row);
        while (recent.size() > RING_MAX) {
            recent.remove(recent.size() - 1);
        }

        if (sev == AlertSeverity.CRITICAL || sev == AlertSeverity.WARN) {
            RiskControlLogService logSvc = riskLogProvider.getIfAvailable();
            if (logSvc != null) {
                String action = (sev.name() + ": " + (actionTaken == null ? "" : actionTaken));
                logSvc.record(day, symbol, ruleType, triggerValue, action);
            }
        }
        return true;
    }

    public List<Map<String, Object>> recent(int limit) {
        int lim = Math.max(1, Math.min(limit <= 0 ? 50 : limit, RING_MAX));
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        int n = Math.min(lim, recent.size());
        for (int i = 0; i < n; i++) {
            out.add(recent.get(i));
        }
        return out;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("cooldownWarnMinutes", props.getAlertCooldownWarnMinutes());
        m.put("cooldownCriticalMinutes", props.getAlertCooldownCriticalMinutes());
        m.put("softTotalPositionPct", props.getSoftTotalPositionPct());
        m.put("softSinglePositionPct", props.getSoftSinglePositionPct());
        m.put("recent", recent(30));
        m.put("hint", "软预算 WARN；熔断/退役 CRITICAL；冷却窗内同类去重");
        return m;
    }

    /** 软预算：总仓/单票接近硬顶时 WARN（不拦截，硬顶仍由仓位工具执行）。 */
    public void checkSoftBudget(LocalDate day, BigDecimal equity, BigDecimal totalPosMv,
                                String symbol, BigDecimal singleMv) {
        if (equity == null || equity.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal softTotal = props.getSoftTotalPositionPct();
        if (softTotal != null && softTotal.compareTo(BigDecimal.ZERO) > 0 && totalPosMv != null) {
            BigDecimal ratio = totalPosMv.divide(equity, 6, java.math.RoundingMode.HALF_UP);
            if (ratio.compareTo(softTotal) >= 0) {
                emit(day, null, "SOFT_TOTAL_POSITION", AlertSeverity.WARN, ratio,
                        "总仓接近软顶 " + softTotal + "（硬顶仍为 maxTotalPosition）");
            }
        }
        BigDecimal softSingle = props.getSoftSinglePositionPct();
        if (softSingle != null && softSingle.compareTo(BigDecimal.ZERO) > 0
                && singleMv != null && symbol != null) {
            BigDecimal ratio = singleMv.divide(equity, 6, java.math.RoundingMode.HALF_UP);
            if (ratio.compareTo(softSingle) >= 0) {
                emit(day, symbol, "SOFT_SINGLE_POSITION", AlertSeverity.WARN, ratio,
                        "单票接近软顶 " + softSingle + "（硬顶仍为 maxSinglePosition）");
            }
        }
    }

    public void clearForTests() {
        lastEmit.clear();
        recent.clear();
    }

    private long cooldownMinutes(AlertSeverity sev) {
        if (sev == AlertSeverity.CRITICAL) {
            return Math.max(0, props.getAlertCooldownCriticalMinutes());
        }
        if (sev == AlertSeverity.WARN) {
            return Math.max(0, props.getAlertCooldownWarnMinutes());
        }
        return Math.max(props.getAlertCooldownWarnMinutes(), 24 * 60L);
    }
}
