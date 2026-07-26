package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 换手 L1/硬顶（P0-104）：按日累计成交额/权益；软顶降仓、硬顶禁开。
 * 印花税仍由 {@link com.quant.stock.trade.TradeCostModel} 扣；不改金叉。
 */
@Service
public class TurnoverGuardService {

    private final QuantProperties props;
    private final RiskAlertService riskAlertService;

    private final ConcurrentHashMap<LocalDate, BigDecimal> dayNotional = new ConcurrentHashMap<LocalDate, BigDecimal>();
    private final AtomicReference<LocalDate> lastDay = new AtomicReference<LocalDate>(null);

    public TurnoverGuardService(QuantProperties props, RiskAlertService riskAlertService) {
        this.props = props;
        this.riskAlertService = riskAlertService;
    }

    public void recordTrade(LocalDate day, BigDecimal amount) {
        if (day == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        dayNotional.merge(day, amount, BigDecimal::add);
        lastDay.set(day);
        if (dayNotional.size() > 60) {
            LocalDate cutoff = day.minusDays(90);
            dayNotional.keySet().removeIf(d -> d.isBefore(cutoff));
        }
    }

    public BigDecimal dayTurnoverRatio(LocalDate day, BigDecimal equity) {
        if (day == null || equity == null || equity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal n = dayNotional.getOrDefault(day, BigDecimal.ZERO);
        return n.divide(equity, 6, RoundingMode.HALF_UP);
    }

    public boolean allowNewOpen(LocalDate day, BigDecimal equity) {
        if (!props.isTurnoverGuardEnabled()) {
            return true;
        }
        BigDecimal hard = props.getTurnoverHardPct();
        if (hard == null || hard.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        BigDecimal r = dayTurnoverRatio(day, equity);
        if (r.compareTo(hard) >= 0) {
            riskAlertService.emit(day, null, "TURNOVER_HARD", AlertSeverity.WARN, r,
                    "日换手≥硬顶 " + hard + "，禁新开");
            return false;
        }
        return true;
    }

    /** 触及软顶时仓位×0.5（静默计算，告警仅在 evaluateAndScale 发出） */
    public BigDecimal positionScaleMultiplier(LocalDate day, BigDecimal equity) {
        if (!props.isTurnoverGuardEnabled()) {
            return BigDecimal.ONE;
        }
        BigDecimal soft = props.getTurnoverSoftPct();
        if (soft == null || soft.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        BigDecimal r = dayTurnoverRatio(day, equity);
        return r.compareTo(soft) >= 0 ? new BigDecimal("0.5") : BigDecimal.ONE;
    }

    public BigDecimal evaluateAndScale(LocalDate day, BigDecimal equity) {
        BigDecimal mul = positionScaleMultiplier(day, equity);
        if (mul.compareTo(BigDecimal.ONE) < 0) {
            riskAlertService.emit(day, null, "TURNOVER_SOFT", AlertSeverity.WARN,
                    dayTurnoverRatio(day, equity),
                    "日换手≥软顶 " + props.getTurnoverSoftPct() + "，仓位×0.5");
        }
        return mul;
    }

    public Map<String, Object> status(BigDecimal equity) {
        LocalDate day = lastDay.get() == null ? LocalDate.now() : lastDay.get();
        BigDecimal r = dayTurnoverRatio(day, equity);
        BigDecimal soft = props.getTurnoverSoftPct();
        BigDecimal hard = props.getTurnoverHardPct();
        boolean allow = !props.isTurnoverGuardEnabled()
                || hard == null || hard.compareTo(BigDecimal.ZERO) <= 0
                || r.compareTo(hard) < 0;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", props.isTurnoverGuardEnabled());
        m.put("day", day.toString());
        m.put("dayNotional", dayNotional.getOrDefault(day, BigDecimal.ZERO));
        m.put("equity", equity);
        m.put("turnoverRatio", r);
        m.put("softPct", soft);
        m.put("hardPct", hard);
        m.put("scaleMultiplier", positionScaleMultiplier(day, equity));
        m.put("allowNewOpen", allow);
        m.put("asOf", LocalDateTime.now().toString());
        m.put("hint", "换手=当日买卖成交额合计/权益；软顶降仓硬顶禁开；印花税 as-of 见 StampTaxAsOf");
        return m;
    }

    public void clearForTests() {
        dayNotional.clear();
        lastDay.set(null);
    }
}
