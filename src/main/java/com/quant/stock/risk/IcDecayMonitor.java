package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IC 衰减监控（P0-125）：滚动 IC 序列 + 半衰期估计；只降仓/告警，不改金叉。
 * IC 样本由 {@link SignalDriftMonitor} 喂入（MA 价差代理；真因子 IC 仍待数据）。
 */
@Service
public class IcDecayMonitor {

    private final QuantProperties props;
    private final RiskAlertService riskAlertService;

    private final Deque<BigDecimal> icSeries = new ArrayDeque<BigDecimal>();
    private volatile BigDecimal lastHalfLife;
    private volatile BigDecimal lastIr;
    private volatile BigDecimal lastMeanIc;
    private volatile boolean decayActive;
    private volatile LocalDateTime lastEvalAt;

    public IcDecayMonitor(QuantProperties props, RiskAlertService riskAlertService) {
        this.props = props;
        this.riskAlertService = riskAlertService;
    }

    public void onIcSample(LocalDate day, BigDecimal ic) {
        if (!props.isIcDecayEnabled() || ic == null) {
            return;
        }
        icSeries.addLast(ic);
        int max = Math.max(10, props.getIcDecayLookback());
        while (icSeries.size() > max) {
            icSeries.removeFirst();
        }
        evaluate(day);
    }

    public BigDecimal positionScaleMultiplier() {
        if (!props.isIcDecayEnabled() || !decayActive) {
            return BigDecimal.ONE;
        }
        return new BigDecimal("0.5");
    }

    public boolean isDecayActive() {
        return props.isIcDecayEnabled() && decayActive;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", props.isIcDecayEnabled());
        m.put("lookback", props.getIcDecayLookback());
        m.put("minHalfLifeBars", props.getIcDecayMinHalfLifeBars());
        m.put("minIr", props.getIcDecayMinIr());
        m.put("sampleCount", icSeries.size());
        m.put("meanIc", lastMeanIc);
        m.put("ir", lastIr);
        m.put("halfLifeBars", lastHalfLife);
        m.put("decayActive", decayActive);
        m.put("scaleMultiplier", positionScaleMultiplier());
        m.put("recentIc", new ArrayList<BigDecimal>(icSeries));
        m.put("lastEvalAt", lastEvalAt == null ? null : lastEvalAt.toString());
        m.put("hint", "IC序列+半衰期；衰减只降仓；代理信号=MA价差，真因子IC仍待");
        return m;
    }

    public void clearForTests() {
        icSeries.clear();
        lastHalfLife = null;
        lastIr = null;
        lastMeanIc = null;
        decayActive = false;
        lastEvalAt = null;
    }

    private void evaluate(LocalDate day) {
        int n = icSeries.size();
        if (n < 8) {
            return;
        }
        List<BigDecimal> arr = new ArrayList<BigDecimal>(icSeries);
        double sum = 0;
        for (BigDecimal v : arr) {
            sum += v.doubleValue();
        }
        double mean = sum / n;
        double ss = 0;
        for (BigDecimal v : arr) {
            double d = v.doubleValue() - mean;
            ss += d * d;
        }
        double std = n < 2 ? 0 : Math.sqrt(ss / (n - 1));
        BigDecimal meanBd = BigDecimal.valueOf(mean).setScale(4, RoundingMode.HALF_UP);
        BigDecimal ir = std < 1e-12 ? null
                : BigDecimal.valueOf(mean / std).setScale(4, RoundingMode.HALF_UP);
        lastMeanIc = meanBd;
        lastIr = ir;
        lastHalfLife = estimateHalfLife(arr);
        lastEvalAt = LocalDateTime.now();

        boolean badHl = lastHalfLife != null
                && props.getIcDecayMinHalfLifeBars() > 0
                && lastHalfLife.compareTo(BigDecimal.valueOf(props.getIcDecayMinHalfLifeBars())) < 0;
        boolean badIr = ir != null && props.getIcDecayMinIr() != null
                && ir.compareTo(props.getIcDecayMinIr()) < 0;
        if (badHl || badIr) {
            decayActive = true;
            riskAlertService.emit(day, null, "IC_DECAY", AlertSeverity.WARN,
                    lastHalfLife == null ? ir : lastHalfLife,
                    "IC衰减：halfLife=" + lastHalfLife + " IR=" + ir + "（仓位×0.5，不改金叉）");
        } else if (ir != null && props.getIcDecayMinIr() != null
                && ir.compareTo(props.getIcDecayMinIr().multiply(new BigDecimal("1.5"))) >= 0) {
            decayActive = false;
        }
    }

    /**
     * 自峰值起首次跌破 peak/2 的步数；无则 null。
     */
    static BigDecimal estimateHalfLife(List<BigDecimal> series) {
        if (series == null || series.size() < 4) {
            return null;
        }
        int peakIdx = 0;
        BigDecimal peak = series.get(0);
        for (int i = 1; i < series.size(); i++) {
            if (series.get(i).abs().compareTo(peak.abs()) > 0) {
                peak = series.get(i);
                peakIdx = i;
            }
        }
        if (peak.abs().compareTo(new BigDecimal("0.01")) < 0) {
            return null;
        }
        BigDecimal half = peak.abs().multiply(new BigDecimal("0.5"));
        for (int i = peakIdx + 1; i < series.size(); i++) {
            if (series.get(i).abs().compareTo(half) <= 0) {
                return BigDecimal.valueOf(i - peakIdx);
            }
        }
        return BigDecimal.valueOf(series.size() - peakIdx);
    }
}
