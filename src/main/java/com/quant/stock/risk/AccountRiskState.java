package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 账户级风控（回测每次独立实例）：
 * <ul>
 *   <li>单日亏损：相对「昨日收盘权益」回撤 ≥ 配置阈值 → 当日禁新开</li>
 *   <li>连亏：连续 N 笔完整开平回合亏损 → 当日禁开、次日恢复</li>
 *   <li>峰值回撤深度：≥reduce 仓位×0.5；≥halt 熔断清仓且禁开</li>
 *   <li>回撤持续期（P0-122）：低于峰值满 N 交易日降仓 / 满 M 日熔断</li>
 * </ul>
 */
public class AccountRiskState {

    public static final String HALT_DEPTH = "DEPTH";
    public static final String HALT_DURATION = "DURATION";

    private final QuantProperties props;

    private final AtomicReference<BigDecimal> peakEquity = new AtomicReference<BigDecimal>(BigDecimal.ZERO);
    /** 昨日收盘权益，作为次日单日亏损基准 */
    private final AtomicReference<BigDecimal> prevCloseEquity = new AtomicReference<BigDecimal>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> dayStartEquity = new AtomicReference<BigDecimal>(BigDecimal.ZERO);
    private final AtomicReference<LocalDate> day = new AtomicReference<LocalDate>(null);
    private final AtomicInteger consecutiveLosses = new AtomicInteger(0);
    /** 禁开截止日期（含当日）；次日即恢复 */
    private final AtomicReference<LocalDate> blockOpenThrough = new AtomicReference<LocalDate>(null);
    /** 连续「低于峰值」的交易日计数（创新高清零） */
    private final AtomicInteger underwaterTradingDays = new AtomicInteger(0);
    /** 已计入 underwater 的最近交易日（同日多 bar 不重复加） */
    private final AtomicReference<LocalDate> lastUnderwaterCountDay = new AtomicReference<LocalDate>(null);

    @Getter
    private volatile boolean halted;

    /** DEPTH / DURATION；未熔断为 null */
    @Getter
    private volatile String haltReason;

    public AccountRiskState(QuantProperties props) {
        this.props = props;
    }

    public BigDecimal getPeakEquity() {
        BigDecimal p = peakEquity.get();
        return p == null ? BigDecimal.ZERO : p;
    }

    public BigDecimal getPrevCloseEquity() {
        BigDecimal p = prevCloseEquity.get();
        return p == null ? BigDecimal.ZERO : p;
    }

    public int getConsecutiveLosses() {
        return consecutiveLosses.get();
    }

    public int getUnderwaterTradingDays() {
        return underwaterTradingDays.get();
    }

    public void reset(BigDecimal initCapital) {
        peakEquity.set(initCapital);
        prevCloseEquity.set(initCapital);
        dayStartEquity.set(initCapital);
        day.set(null);
        consecutiveLosses.set(0);
        blockOpenThrough.set(null);
        underwaterTradingDays.set(0);
        lastUnderwaterCountDay.set(null);
        halted = false;
        haltReason = null;
    }

    /** 导出可落库状态（重启恢复）。 */
    public Map<String, String> exportState() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("peakEquity", getPeakEquity().toPlainString());
        m.put("prevCloseEquity", getPrevCloseEquity().toPlainString());
        BigDecimal ds = dayStartEquity.get();
        m.put("dayStartEquity", ds == null ? "0" : ds.toPlainString());
        LocalDate d = day.get();
        m.put("day", d == null ? "" : d.toString());
        m.put("consecutiveLosses", String.valueOf(consecutiveLosses.get()));
        LocalDate block = blockOpenThrough.get();
        m.put("blockOpenThrough", block == null ? "" : block.toString());
        m.put("underwaterTradingDays", String.valueOf(underwaterTradingDays.get()));
        LocalDate uw = lastUnderwaterCountDay.get();
        m.put("lastUnderwaterCountDay", uw == null ? "" : uw.toString());
        m.put("halted", String.valueOf(halted));
        m.put("haltReason", haltReason == null ? "" : haltReason);
        return m;
    }

    /** 从落库快照恢复；缺字段时安全跳过。 */
    public void importState(Map<String, String> m) {
        if (m == null || m.isEmpty()) {
            return;
        }
        BigDecimal peak = parseBd(m.get("peakEquity"));
        if (peak != null) {
            peakEquity.set(peak);
        }
        BigDecimal prev = parseBd(m.get("prevCloseEquity"));
        if (prev != null) {
            prevCloseEquity.set(prev);
        }
        BigDecimal ds = parseBd(m.get("dayStartEquity"));
        if (ds != null) {
            dayStartEquity.set(ds);
        }
        day.set(parseDate(m.get("day")));
        consecutiveLosses.set(parseInt(m.get("consecutiveLosses"), 0));
        blockOpenThrough.set(parseDate(m.get("blockOpenThrough")));
        underwaterTradingDays.set(parseInt(m.get("underwaterTradingDays"), 0));
        lastUnderwaterCountDay.set(parseDate(m.get("lastUnderwaterCountDay")));
        halted = "true".equalsIgnoreCase(String.valueOf(m.get("halted")));
        String hr = m.get("haltReason");
        haltReason = hr == null || hr.trim().isEmpty() ? null : hr.trim();
    }

    private static BigDecimal parseBd(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 每个 bar 调用：跨日时用昨收权益作为当日基准；更新峰值、持续期与熔断。
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
            underwaterTradingDays.set(0);
            lastUnderwaterCountDay.set(tradeDay);
        } else if (equity.compareTo(peak) < 0) {
            LocalDate counted = lastUnderwaterCountDay.get();
            if (counted == null || !counted.equals(tradeDay)) {
                underwaterTradingDays.incrementAndGet();
                lastUnderwaterCountDay.set(tradeDay);
            }
        }

        BigDecimal dd = drawdown(equity);
        if (dd.compareTo(props.getDrawdownHaltPct()) >= 0) {
            halted = true;
            if (haltReason == null) {
                haltReason = HALT_DEPTH;
            }
        }
        int durationHalt = props.getDrawdownDurationHaltDays();
        if (durationHalt > 0 && underwaterTradingDays.get() >= durationHalt) {
            halted = true;
            if (haltReason == null) {
                haltReason = HALT_DURATION;
            }
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
        if (halted || dd.compareTo(props.getDrawdownHaltPct()) >= 0) {
            return BigDecimal.ZERO;
        }
        if (dd.compareTo(props.getDrawdownReducePct()) >= 0) {
            return new BigDecimal("0.5");
        }
        int durationReduce = props.getDrawdownDurationReduceDays();
        if (durationReduce > 0 && underwaterTradingDays.get() >= durationReduce) {
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
