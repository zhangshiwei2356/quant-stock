package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 账户级风控（回测每次独立实例）：
 * <ul>
 *   <li>单日亏损：相对「昨日收盘权益」回撤 ≥ 配置阈值 → 当日禁新开</li>
 *   <li>连亏：连续 N 笔完整开平回合亏损 → 当日禁开、次日恢复</li>
 *   <li>峰值回撤：≥reduce 仓位×0.5；≥halt 熔断清仓且禁开</li>
 * </ul>
 */
public class AccountRiskState {

    private final QuantProperties props;

    private final AtomicReference<BigDecimal> peakEquity = new AtomicReference<BigDecimal>(BigDecimal.ZERO);
    /** 昨日收盘权益，作为次日单日亏损基准 */
    private final AtomicReference<BigDecimal> prevCloseEquity = new AtomicReference<BigDecimal>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> dayStartEquity = new AtomicReference<BigDecimal>(BigDecimal.ZERO);
    private final AtomicReference<LocalDate> day = new AtomicReference<LocalDate>(null);
    private final AtomicInteger consecutiveLosses = new AtomicInteger(0);
    /** 禁开截止日期（含当日）；次日即恢复 */
    private final AtomicReference<LocalDate> blockOpenThrough = new AtomicReference<LocalDate>(null);

    @Getter
    private volatile boolean halted;

    public AccountRiskState(QuantProperties props) {
        this.props = props;
    }

    public void reset(BigDecimal initCapital) {
        peakEquity.set(initCapital);
        prevCloseEquity.set(initCapital);
        dayStartEquity.set(initCapital);
        day.set(null);
        consecutiveLosses.set(0);
        blockOpenThrough.set(null);
        halted = false;
    }

    /**
     * 每个 bar 调用：跨日时用昨收权益作为当日基准；更新峰值与熔断。
     */
    public void onEquity(LocalDate tradeDay, BigDecimal equity) {
        if (tradeDay == null || equity == null) {
            return;
        }
        LocalDate cur = day.get();
        if (cur == null || !cur.equals(tradeDay)) {
            day.set(tradeDay);
            BigDecimal base = prevCloseEquity.get();
            if (base == null || base.compareTo(BigDecimal.ZERO) <= 0) {
                base = equity;
            }
            dayStartEquity.set(base);
        }
        BigDecimal peak = peakEquity.get();
        if (equity.compareTo(peak) > 0) {
            peakEquity.set(equity);
        }
        BigDecimal dd = drawdown(equity);
        if (dd.compareTo(props.getDrawdownHaltPct()) >= 0) {
            halted = true;
        }
    }

    /** 日/序列收盘后固化昨收权益 */
    public void onDayClose(BigDecimal closeEquity) {
        if (closeEquity != null) {
            prevCloseEquity.set(closeEquity);
        }
    }

    /**
     * 完整开→平回合结果。连亏达限：当日禁开，次日恢复。
     */
    public void onClosedRound(boolean win, LocalDate tradeDay) {
        if (win) {
            consecutiveLosses.set(0);
            return;
        }
        int n = consecutiveLosses.incrementAndGet();
        if (n >= props.getConsecutiveLossLimit()) {
            blockOpenThrough.set(tradeDay);
            consecutiveLosses.set(0);
        }
    }

    /** @deprecated 兼容旧调用，等同 {@link #onClosedRound} */
    @Deprecated
    public void onClosedTrade(boolean win, LocalDate tradeDay) {
        onClosedRound(win, tradeDay);
    }

    public boolean allowNewOpen(LocalDate tradeDay, BigDecimal equity) {
        if (halted) {
            return false;
        }
        LocalDate block = blockOpenThrough.get();
        if (block != null && tradeDay != null && !tradeDay.isAfter(block)) {
            return false;
        }
        onEquity(tradeDay, equity);
        BigDecimal start = dayStartEquity.get();
        if (start != null && start.compareTo(BigDecimal.ZERO) > 0 && equity != null) {
            BigDecimal dayLoss = start.subtract(equity).divide(start, 6, RoundingMode.HALF_UP);
            if (dayLoss.compareTo(props.getDailyLossLimitPct()) >= 0) {
                return false;
            }
        }
        return true;
    }

    public BigDecimal positionScale(BigDecimal equity) {
        BigDecimal dd = drawdown(equity);
        if (dd.compareTo(props.getDrawdownHaltPct()) >= 0) {
            return BigDecimal.ZERO;
        }
        if (dd.compareTo(props.getDrawdownReducePct()) >= 0) {
            return new BigDecimal("0.5");
        }
        return BigDecimal.ONE;
    }

    public BigDecimal drawdown(BigDecimal equity) {
        BigDecimal peak = peakEquity.get();
        if (peak == null || peak.compareTo(BigDecimal.ZERO) <= 0 || equity == null) {
            return BigDecimal.ZERO;
        }
        if (equity.compareTo(peak) >= 0) {
            return BigDecimal.ZERO;
        }
        return peak.subtract(equity).divide(peak, 6, RoundingMode.HALF_UP);
    }
}
