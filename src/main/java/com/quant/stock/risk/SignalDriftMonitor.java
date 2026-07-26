package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.strategy.IndicatorSignalUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 信号漂移监控（P0-90 可落部分）：滚动胜率 + MA 价差 vs 次日收益的滚动 IC。
 * 冷却确认后可联动退役；不改金叉定义、不报复满仓。
 */
@Service
public class SignalDriftMonitor {

    private final QuantProperties props;
    private final RiskAlertService riskAlertService;
    private final StrategyRetirementService strategyRetirementService;

    private final Deque<Boolean> recentWins = new ArrayDeque<Boolean>();
    private int belowThresholdStreak;
    private volatile BigDecimal lastIc;
    private volatile BigDecimal lastWinRate;
    private volatile LocalDateTime lastEvalAt;
    private volatile boolean killArmed;

    public SignalDriftMonitor(QuantProperties props,
                              RiskAlertService riskAlertService,
                              StrategyRetirementService strategyRetirementService) {
        this.props = props;
        this.riskAlertService = riskAlertService;
        this.strategyRetirementService = strategyRetirementService;
    }

    /** 记录一笔完整回合结果，更新滚动胜率 */
    public void onClosedRound(boolean win, LocalDate tradeDay) {
        if (!props.isSignalDriftEnabled()) {
            return;
        }
        recentWins.addLast(win);
        int look = Math.max(5, props.getDriftLookbackRounds());
        while (recentWins.size() > look) {
            recentWins.removeFirst();
        }
        evaluateWinRate(tradeDay);
    }

    /** 结构突变挂接（P0-120）：加速漂移确认，不伪造成交。 */
    public void onStructuralBreakHint(LocalDate tradeDay, BigDecimal score) {
        if (!props.isSignalDriftEnabled()) {
            return;
        }
        riskAlertService.emit(tradeDay, null, "SIGNAL_DRIFT_STRUCT_BREAK", AlertSeverity.WARN, score,
                "结构突变挂漂移确认");
        belowThresholdStreak++;
        maybeKill(tradeDay, "STRUCT_BREAK");
    }

    /**
     * 用当前标的指标估计滚动 Pearson IC（信号=(ma5-ma20)/ma20，标签=次日收益）。
     */
    public BigDecimal evaluateRollingIc(List<BarDTO> bars, int index, LocalDate tradeDay) {
        if (!props.isSignalDriftEnabled() || bars == null || index < 40) {
            return null;
        }
        int look = Math.max(20, props.getDriftIcLookbackDays());
        int from = Math.max(20, index - look);
        IndicatorSignalUtil.IndicatorBundle ind = IndicatorSignalUtil.precompute(bars);
        double[] xs = new double[look];
        double[] ys = new double[look];
        int n = 0;
        for (int i = from; i < index && i + 1 < bars.size(); i++) {
            if (Double.isNaN(ind.ma5[i]) || Double.isNaN(ind.ma20[i]) || ind.ma20[i] == 0) {
                continue;
            }
            BigDecimal c0 = bars.get(i).getClose();
            BigDecimal c1 = bars.get(i + 1).getClose();
            if (c0 == null || c1 == null || c0.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            xs[n] = (ind.ma5[i] - ind.ma20[i]) / ind.ma20[i];
            ys[n] = c1.subtract(c0).divide(c0, 8, RoundingMode.HALF_UP).doubleValue();
            n++;
        }
        if (n < 15) {
            return null;
        }
        BigDecimal ic = pearson(xs, ys, n);
        lastIc = ic;
        lastEvalAt = LocalDateTime.now();
        BigDecimal minIc = props.getDriftMinIc();
        if (ic != null && minIc != null && ic.compareTo(minIc) < 0) {
            riskAlertService.emit(tradeDay, null, "SIGNAL_DRIFT_IC", AlertSeverity.WARN, ic,
                    "滚动IC低于阈 " + minIc + "（只降权/Kill，不改金叉）");
            belowThresholdStreak++;
            maybeKill(tradeDay, "IC");
        } else {
            belowThresholdStreak = Math.max(0, belowThresholdStreak - 1);
        }
        return ic;
    }

    /** 运维/接口状态快照 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", props.isSignalDriftEnabled());
        m.put("lookbackRounds", props.getDriftLookbackRounds());
        m.put("minWinRate", props.getDriftMinWinRate());
        m.put("minIc", props.getDriftMinIc());
        m.put("confirmRounds", props.getDriftConfirmRounds());
        m.put("autoRetireOnDrift", props.isAutoRetireOnSignalDrift());
        m.put("sampleRounds", recentWins.size());
        m.put("rollingWinRate", lastWinRate);
        m.put("rollingIc", lastIc);
        m.put("belowThresholdStreak", belowThresholdStreak);
        m.put("killArmed", killArmed);
        m.put("lastEvalAt", lastEvalAt == null ? null : lastEvalAt.toString());
        // P0-90 边界：本地无截面真因子收益面板；factor_daily 仅为技术缓存，不作 alpha IC
        m.put("icSource", "MA_SPREAD_PROXY");
        m.put("factorIcStatus", "UNAVAILABLE");
        m.put("factorIcHint", "真因子/截面 IC 缺数据；factor_daily=技术缓存≠alpha；Kill 只基于代理IC+胜率");
        m.put("hint", "滚动胜率+MA价差代理IC；冷却确认后可退役；不静默改金叉");
        return m;
    }

    /** 单测重置 */
    public void clearForTests() {
        recentWins.clear();
        belowThresholdStreak = 0;
        lastIc = null;
        lastWinRate = null;
        lastEvalAt = null;
        killArmed = false;
    }

    private void evaluateWinRate(LocalDate tradeDay) {
        int need = Math.max(5, props.getDriftLookbackRounds());
        if (recentWins.size() < need) {
            return;
        }
        int wins = 0;
        for (Boolean w : recentWins) {
            if (Boolean.TRUE.equals(w)) {
                wins++;
            }
        }
        BigDecimal wr = BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(recentWins.size()), 4, RoundingMode.HALF_UP);
        lastWinRate = wr;
        lastEvalAt = LocalDateTime.now();
        BigDecimal min = props.getDriftMinWinRate();
        if (min != null && wr.compareTo(min) < 0) {
            riskAlertService.emit(tradeDay, null, "SIGNAL_DRIFT_WINRATE", AlertSeverity.WARN, wr,
                    "滚动胜率低于阈 " + min);
            belowThresholdStreak++;
            maybeKill(tradeDay, "WINRATE");
        } else {
            belowThresholdStreak = 0;
        }
    }

    private void maybeKill(LocalDate tradeDay, String reason) {
        int confirm = Math.max(1, props.getDriftConfirmRounds());
        if (belowThresholdStreak < confirm) {
            return;
        }
        killArmed = true;
        riskAlertService.emit(tradeDay, null, "SIGNAL_DRIFT_KILL", AlertSeverity.CRITICAL,
                BigDecimal.valueOf(belowThresholdStreak),
                "漂移确认×" + confirm + "（" + reason + "）");
        if (props.isAutoRetireOnSignalDrift() && !strategyRetirementService.isRetired()) {
            strategyRetirementService.retire(tradeDay, "SIGNAL_DRIFT",
                    "信号漂移 Kill：" + reason + " streak=" + belowThresholdStreak);
        }
    }

    private static BigDecimal pearson(double[] x, double[] y, int n) {
        double sx = 0, sy = 0;
        for (int i = 0; i < n; i++) {
            sx += x[i];
            sy += y[i];
        }
        double mx = sx / n;
        double my = sy / n;
        double num = 0, dx = 0, dy = 0;
        for (int i = 0; i < n; i++) {
            double a = x[i] - mx;
            double b = y[i] - my;
            num += a * b;
            dx += a * a;
            dy += b * b;
        }
        if (dx <= 1e-18 || dy <= 1e-18) {
            return null;
        }
        return BigDecimal.valueOf(num / Math.sqrt(dx * dy)).setScale(4, RoundingMode.HALF_UP);
    }
}
