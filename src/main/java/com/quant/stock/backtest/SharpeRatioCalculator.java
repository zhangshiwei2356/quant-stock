package com.quant.stock.backtest;

import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从权益曲线计算年化夏普（策略总览展示用，<strong>不参与</strong>综合评分加权）。
 * <ul>
 *   <li>无风险利率按 0</li>
 *   <li>相邻权益点简单收益率 {@code r_t = E_t / E_{t-1} - 1}</li>
 *   <li>年化因子 {@code √N}：日线 252、周线 52、月线 12；分钟按约 240 分钟/交易日 × 252</li>
 *   <li>有效收益点 &lt; 2、标准差为 0、权益非正 → {@code null}</li>
 * </ul>
 */
public final class SharpeRatioCalculator {

    private static final int SCALE = 4;
    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    /** A 股近似交易日 */
    private static final int TRADING_DAYS = 252;
    /** 日盘约 240 分钟 */
    private static final int MINUTES_PER_DAY = 240;

    private SharpeRatioCalculator() {
    }

    /**
     * @param equityCurve 权益序列（至少 2 点才可能有收益）
     * @param period      K 线周期（如 DAY / MIN_1）；空则按 DAY
     * @return 年化夏普，无法计算时 null
     */
    public static BigDecimal fromEquityCurve(List<BigDecimal> equityCurve, String period) {
        List<BigDecimal> returns = periodReturns(equityCurve);
        if (returns.size() < 2) {
            return null;
        }
        BigDecimal mean = mean(returns);
        BigDecimal std = sampleStdDev(returns, mean);
        if (std == null || std.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        double n = annualizationFactor(period);
        if (n <= 0) {
            return null;
        }
        BigDecimal raw = mean.divide(std, MC);
        BigDecimal ann = raw.multiply(BigDecimal.valueOf(Math.sqrt(n)), MC);
        return ann.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 相邻权益简单收益率；跳过非正权益或非法点。 */
    static List<BigDecimal> periodReturns(List<BigDecimal> equityCurve) {
        List<BigDecimal> out = new ArrayList<BigDecimal>();
        if (equityCurve == null || equityCurve.size() < 2) {
            return out;
        }
        for (int i = 1; i < equityCurve.size(); i++) {
            BigDecimal prev = equityCurve.get(i - 1);
            BigDecimal cur = equityCurve.get(i);
            if (prev == null || cur == null || prev.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            out.add(cur.divide(prev, MC).subtract(BigDecimal.ONE));
        }
        return out;
    }

    /** 每年收益期数（用于 √N 年化）。 */
    static double annualizationFactor(String period) {
        String p = StringUtils.hasText(period) ? period.trim().toUpperCase(Locale.ROOT) : "DAY";
        if ("DAY".equals(p)) {
            return TRADING_DAYS;
        }
        if ("WEEK".equals(p)) {
            return 52;
        }
        if ("MONTH".equals(p)) {
            return 12;
        }
        if ("MIN_1".equals(p) || "MIN1".equals(p) || "1MIN".equals(p)) {
            return MINUTES_PER_DAY * TRADING_DAYS;
        }
        if ("MIN_5".equals(p) || "MIN5".equals(p) || "5MIN".equals(p)) {
            return (MINUTES_PER_DAY / 5.0) * TRADING_DAYS;
        }
        if ("MIN_15".equals(p) || "MIN15".equals(p) || "15MIN".equals(p)) {
            return (MINUTES_PER_DAY / 15.0) * TRADING_DAYS;
        }
        if ("MIN_30".equals(p) || "MIN30".equals(p) || "30MIN".equals(p)) {
            return (MINUTES_PER_DAY / 30.0) * TRADING_DAYS;
        }
        if ("MIN_60".equals(p) || "MIN60".equals(p) || "60MIN".equals(p)) {
            return (MINUTES_PER_DAY / 60.0) * TRADING_DAYS;
        }
        return TRADING_DAYS;
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        return sum.divide(BigDecimal.valueOf(values.size()), MC);
    }

    /** 样本标准差（除以 n−1）；n&lt;2 返回 null。 */
    private static BigDecimal sampleStdDev(List<BigDecimal> values, BigDecimal mean) {
        int n = values.size();
        if (n < 2) {
            return null;
        }
        BigDecimal sumSq = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            BigDecimal d = v.subtract(mean);
            sumSq = sumSq.add(d.multiply(d, MC));
        }
        BigDecimal variance = sumSq.divide(BigDecimal.valueOf(n - 1L), MC);
        if (variance.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        double sd = Math.sqrt(variance.doubleValue());
        if (!Double.isFinite(sd) || sd <= 0) {
            return null;
        }
        return BigDecimal.valueOf(sd);
    }
}
