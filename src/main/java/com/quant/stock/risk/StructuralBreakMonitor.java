package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 结构突变监控（P0-120）：双窗收益均值差；触发后挂漂移/降仓，不改金叉。
 */
@Service
public class StructuralBreakMonitor {

    private final QuantProperties props;
    private final RiskAlertService riskAlertService;
    private final SignalDriftMonitor signalDriftMonitor;

    private final AtomicReference<BigDecimal> lastScore = new AtomicReference<BigDecimal>();
    private volatile boolean breakActive;
    private volatile LocalDateTime lastEvalAt;
    private int confirmStreak;

    public StructuralBreakMonitor(QuantProperties props,
                                  RiskAlertService riskAlertService,
                                  SignalDriftMonitor signalDriftMonitor) {
        this.props = props;
        this.riskAlertService = riskAlertService;
        this.signalDriftMonitor = signalDriftMonitor;
    }

    /** 突变活跃时仓位额外 ×0.5（与压力情景相乘） */
    public BigDecimal positionScaleMultiplier() {
        if (!props.isStructuralBreakEnabled() || !breakActive) {
            return BigDecimal.ONE;
        }
        return new BigDecimal("0.5");
    }

    public boolean isBreakActive() {
        return props.isStructuralBreakEnabled() && breakActive;
    }

    /**
     * 用收盘价序列做前后窗均值差 / 合成波动（简易突变分）。
     */
    public BigDecimal evaluate(List<BarDTO> bars, int index, LocalDate tradeDay) {
        if (!props.isStructuralBreakEnabled() || bars == null || index < 40) {
            return null;
        }
        int w = Math.max(10, props.getStructuralBreakWindow());
        if (index < w * 2) {
            return null;
        }
        double meanOld = meanRet(bars, index - 2 * w + 1, index - w);
        double meanNew = meanRet(bars, index - w + 1, index);
        double vol = Math.max(1e-6, stdRet(bars, index - 2 * w + 1, index));
        double score = Math.abs(meanNew - meanOld) / vol;
        BigDecimal sc = BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
        lastScore.set(sc);
        lastEvalAt = LocalDateTime.now();

        BigDecimal thr = props.getStructuralBreakThreshold();
        if (thr != null && sc.compareTo(thr) >= 0) {
            confirmStreak++;
            riskAlertService.emit(tradeDay, null, "STRUCTURAL_BREAK", AlertSeverity.WARN, sc,
                    "双窗收益均值突变分≥" + thr + "（挂漂移/降仓，不改金叉）");
            if (confirmStreak >= Math.max(1, props.getStructuralBreakConfirmBars())) {
                breakActive = true;
                // 挂漂移：记一笔假亏损回合，加速漂移确认（不伪造成交）
                signalDriftMonitor.onStructuralBreakHint(tradeDay, sc);
            }
        } else {
            confirmStreak = 0;
            // 分数回落后解除突变降仓（Kill/退役仍由漂移/熔断管）
            if (breakActive && thr != null && sc.compareTo(thr.multiply(new BigDecimal("0.5"))) < 0) {
                breakActive = false;
            }
        }
        return sc;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", props.isStructuralBreakEnabled());
        m.put("window", props.getStructuralBreakWindow());
        m.put("threshold", props.getStructuralBreakThreshold());
        m.put("confirmBars", props.getStructuralBreakConfirmBars());
        m.put("breakActive", breakActive);
        m.put("lastScore", lastScore.get());
        m.put("confirmStreak", confirmStreak);
        m.put("scaleMultiplier", positionScaleMultiplier());
        m.put("lastEvalAt", lastEvalAt == null ? null : lastEvalAt.toString());
        m.put("hint", "结构突变只降仓并挂漂移；不改金叉公式");
        return m;
    }

    public void clearForTests() {
        lastScore.set(null);
        breakActive = false;
        lastEvalAt = null;
        confirmStreak = 0;
    }

    /** 回测纯函数：单 bar 是否越过突变阈（无确认粘性）。 */
    public static boolean crossesThreshold(List<BarDTO> bars, int index, int window, BigDecimal threshold) {
        BigDecimal sc = scoreAt(bars, index, window);
        return sc != null && threshold != null && sc.compareTo(threshold) >= 0;
    }

    public static BigDecimal scoreAt(List<BarDTO> bars, int index, int window) {
        int w = Math.max(10, window);
        if (bars == null || index < w * 2) {
            return null;
        }
        double meanOld = meanRet(bars, index - 2 * w + 1, index - w);
        double meanNew = meanRet(bars, index - w + 1, index);
        double vol = Math.max(1e-6, stdRet(bars, index - 2 * w + 1, index));
        return BigDecimal.valueOf(Math.abs(meanNew - meanOld) / vol).setScale(4, RoundingMode.HALF_UP);
    }

    private static double meanRet(List<BarDTO> bars, int from, int to) {
        double sum = 0;
        int n = 0;
        for (int i = Math.max(1, from); i <= to && i < bars.size(); i++) {
            BigDecimal c0 = bars.get(i - 1).getClose();
            BigDecimal c1 = bars.get(i).getClose();
            if (c0 == null || c1 == null || c0.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            sum += c1.subtract(c0).divide(c0, 8, RoundingMode.HALF_UP).doubleValue();
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    private static double stdRet(List<BarDTO> bars, int from, int to) {
        double m = meanRet(bars, from, to);
        double ss = 0;
        int n = 0;
        for (int i = Math.max(1, from); i <= to && i < bars.size(); i++) {
            BigDecimal c0 = bars.get(i - 1).getClose();
            BigDecimal c1 = bars.get(i).getClose();
            if (c0 == null || c1 == null || c0.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            double r = c1.subtract(c0).divide(c0, 8, RoundingMode.HALF_UP).doubleValue();
            ss += (r - m) * (r - m);
            n++;
        }
        return n < 2 ? 0 : Math.sqrt(ss / (n - 1));
    }
}
