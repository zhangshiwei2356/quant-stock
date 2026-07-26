package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.strategy.IndicatorSignalUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预注册压力情景评估（P0-96）：ADV 断崖降仓；其余告警/沿用熔断。
 * 不改金叉开仓方向。
 */
@Service
public class StressScenarioService {

    private final QuantProperties props;
    private final RiskAlertService riskAlertService;

    /** 标的 → 当日 ADV 断崖 */
    private final ConcurrentHashMap<String, Boolean> advCliffByCode = new ConcurrentHashMap<String, Boolean>();
    private final ConcurrentHashMap<String, Boolean> limitLockByCode = new ConcurrentHashMap<String, Boolean>();
    private volatile boolean drawdownRegime;
    private volatile boolean correlationSpike;
    private volatile LocalDateTime lastEvalAt;

    public StressScenarioService(QuantProperties props, RiskAlertService riskAlertService) {
        this.props = props;
        this.riskAlertService = riskAlertService;
    }

    public BigDecimal positionScaleMultiplier() {
        if (!props.isStressScenarioEnabled()) {
            return BigDecimal.ONE;
        }
        for (Boolean v : advCliffByCode.values()) {
            if (Boolean.TRUE.equals(v)) {
                return new BigDecimal("0.5");
            }
        }
        return BigDecimal.ONE;
    }

    /**
     * 用当前标的日线评估 ADV 断崖 / 跌停死锁。
     */
    public void evaluateOnBar(String code, List<BarDTO> bars, int index, LocalDate tradeDay,
                              int limitDownFailDays, boolean accountHalted) {
        if (!props.isStressScenarioEnabled()) {
            advCliffByCode.clear();
            limitLockByCode.clear();
            drawdownRegime = false;
            return;
        }
        lastEvalAt = LocalDateTime.now();
        drawdownRegime = accountHalted;

        boolean cliff = isAdvCliff(bars, index, props.getStressAdvCliffRatio());
        if (code != null) {
            advCliffByCode.put(code, cliff);
        }
        if (cliff) {
            riskAlertService.emit(tradeDay, code, "STRESS_ADV_CLIFF", AlertSeverity.WARN,
                    advRatio(bars, index), "ADV断崖：仓位系数×0.5（不改金叉）");
        }

        boolean lock = limitDownFailDays >= 2;
        if (code != null) {
            limitLockByCode.put(code, lock);
        }
        if (lock) {
            riskAlertService.emit(tradeDay, code, "STRESS_LIMIT_LOCK", AlertSeverity.WARN,
                    BigDecimal.valueOf(limitDownFailDays), "连续跌停挂卖失败，一字板情景告警");
        }
    }

    public void markCorrelationSpike(boolean spike, LocalDate day, BigDecimal avgCorr) {
        correlationSpike = spike;
        if (spike) {
            riskAlertService.emit(day, null, "STRESS_CORRELATION", AlertSeverity.WARN,
                    avgCorr, "相关尖峰情景（只告警）");
        }
    }

    public Map<String, Object> catalogAndStatus() {
        List<Map<String, Object>> catalog = new ArrayList<Map<String, Object>>();
        for (StressScenario s : StressScenario.values()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", s.name());
            row.put("title", s.getTitle());
            row.put("description", s.getDescription());
            row.put("action", s.getAction());
            row.put("active", isActive(s));
            catalog.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", props.isStressScenarioEnabled());
        m.put("advCliffRatio", props.getStressAdvCliffRatio());
        m.put("stressScale", positionScaleMultiplier());
        m.put("cliffCodes", new ArrayList<String>(filterTrue(advCliffByCode)));
        m.put("lastEvalAt", lastEvalAt == null ? null : lastEvalAt.toString());
        m.put("scenarios", catalog);
        m.put("hint", "预注册情景；ADV断崖降仓；不改金叉；压力不回写生产信号参数");
        return m;
    }

    public static boolean isAdvCliff(List<BarDTO> bars, int index, BigDecimal cliffRatio) {
        if (cliffRatio == null || cliffRatio.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (bars == null || index < 60) {
            return false;
        }
        long adv20 = IndicatorSignalUtil.avgVolume(bars, index, 20);
        long adv60 = IndicatorSignalUtil.avgVolume(bars, index, 60);
        if (adv60 <= 0) {
            return false;
        }
        BigDecimal ratio = BigDecimal.valueOf(adv20)
                .divide(BigDecimal.valueOf(adv60), 6, RoundingMode.HALF_UP);
        return ratio.compareTo(cliffRatio) < 0;
    }

    public void clearForTests() {
        advCliffByCode.clear();
        limitLockByCode.clear();
        drawdownRegime = false;
        correlationSpike = false;
        lastEvalAt = null;
    }

    private boolean isActive(StressScenario s) {
        switch (s) {
            case ADV_CLIFF:
                return positionScaleMultiplier().compareTo(BigDecimal.ONE) < 0;
            case LIMIT_LOCK:
                return !filterTrue(limitLockByCode).isEmpty();
            case CORRELATION_SPIKE:
                return correlationSpike;
            case DRAWDOWN_REGIME:
                return drawdownRegime;
            case LIQUIDITY_DROUGHT:
                return false;
            default:
                return false;
        }
    }

    private BigDecimal advRatio(List<BarDTO> bars, int index) {
        long adv20 = IndicatorSignalUtil.avgVolume(bars, index, 20);
        long adv60 = IndicatorSignalUtil.avgVolume(bars, index, 60);
        if (adv60 <= 0) {
            return null;
        }
        return BigDecimal.valueOf(adv20).divide(BigDecimal.valueOf(adv60), 4, RoundingMode.HALF_UP);
    }

    private static List<String> filterTrue(Map<String, Boolean> map) {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, Boolean> e : map.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }
}
