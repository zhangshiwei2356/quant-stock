package com.quant.stock.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略综合评分（满分 100）：基于已有回测摘要指标加权，非实盘预测。
 * <ul>
 *   <li>收益 30：平均总收益率（−20%→0 分，+50%→满分）</li>
 *   <li>回撤 25：平均最大回撤（≤5%→满分，≥40%→0）</li>
 *   <li>胜率 20：平均胜率（0%→0，100%→满分；无胜率数据则本项 0）</li>
 *   <li>盈利占比 15：收益&gt;0 的回测条数占比</li>
 *   <li>样本 10：回测次数（0→0，≥10→满分）</li>
 * </ul>
 * 无回测时 {@code score=null}。
 */
public final class StrategyScoreCalculator {

    public static final int SCORE_MAX = 100;
    private static final int SCALE = 2;

    private static final int W_RETURN = 30;
    private static final int W_DRAWDOWN = 25;
    private static final int W_WIN = 20;
    private static final int W_POSITIVE = 15;
    private static final int W_SAMPLE = 10;

    private StrategyScoreCalculator() {
    }

    /**
     * @param avgTotalRate   平均总收益率（小数，如 0.12）
     * @param avgMaxDrawdown 平均最大回撤（小数，绝对值语义，如 0.15）
     * @param avgWinRate     平均胜率（小数）；可为 null
     * @param positiveRatio  盈利回测占比 [0,1]；可为 null
     * @param runCount       回测条数
     */
    public static Map<String, Object> score(BigDecimal avgTotalRate,
                                            BigDecimal avgMaxDrawdown,
                                            BigDecimal avgWinRate,
                                            BigDecimal positiveRatio,
                                            int runCount) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("scoreMax", SCORE_MAX);
        if (runCount <= 0) {
            out.put("score", null);
            out.put("grade", null);
            out.put("note", "暂无回测记录，无法评分");
            out.put("components", Collections.emptyList());
            return out;
        }

        List<Map<String, Object>> components = new ArrayList<Map<String, Object>>();
        BigDecimal pReturn = pointsReturn(avgTotalRate);
        components.add(component("return", "收益", pReturn, W_RETURN,
                avgTotalRate == null ? "均收益 —" : ("均收益 " + pctLabel(avgTotalRate))));

        BigDecimal pDd = pointsDrawdown(avgMaxDrawdown);
        components.add(component("drawdown", "回撤", pDd, W_DRAWDOWN,
                avgMaxDrawdown == null ? "均回撤 —" : ("均回撤 " + pctLabel(avgMaxDrawdown))));

        BigDecimal pWin = pointsWin(avgWinRate);
        components.add(component("winRate", "胜率", pWin, W_WIN,
                avgWinRate == null ? "无胜率数据" : ("均胜率 " + pctLabel(avgWinRate))));

        BigDecimal pPos = pointsPositive(positiveRatio);
        components.add(component("positiveRatio", "盈利占比", pPos, W_POSITIVE,
                positiveRatio == null ? "占比 —" : ("盈利占比 " + pctLabel(positiveRatio))));

        BigDecimal pSample = pointsSample(runCount);
        components.add(component("sample", "样本", pSample, W_SAMPLE,
                "回测 " + runCount + " 次"));

        BigDecimal total = pReturn.add(pDd).add(pWin).add(pPos).add(pSample)
                .setScale(SCALE, RoundingMode.HALF_UP);
        int scoreInt = total.setScale(0, RoundingMode.HALF_UP).intValue();
        if (scoreInt < 0) {
            scoreInt = 0;
        }
        if (scoreInt > SCORE_MAX) {
            scoreInt = SCORE_MAX;
        }

        out.put("score", scoreInt);
        out.put("grade", gradeOf(scoreInt));
        out.put("note", "由历史回测加权；样本少时仅供对照");
        out.put("components", components);
        return out;
    }

    static BigDecimal pointsReturn(BigDecimal avgRate) {
        // −0.20 → 0；+0.50 → 30
        return linearClamp(avgRate, bd("-0.20"), bd("0.50"), W_RETURN);
    }

    static BigDecimal pointsDrawdown(BigDecimal avgDd) {
        // 0.05 → 25；0.40 → 0（回撤越大分越低）
        if (avgDd == null) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal abs = avgDd.abs();
        return linearClampInverse(abs, bd("0.05"), bd("0.40"), W_DRAWDOWN);
    }

    static BigDecimal pointsWin(BigDecimal avgWin) {
        if (avgWin == null) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return linearClamp(avgWin, BigDecimal.ZERO, BigDecimal.ONE, W_WIN);
    }

    static BigDecimal pointsPositive(BigDecimal ratio) {
        if (ratio == null) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return linearClamp(ratio, BigDecimal.ZERO, BigDecimal.ONE, W_POSITIVE);
    }

    static BigDecimal pointsSample(int runCount) {
        if (runCount <= 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        if (runCount >= 10) {
            return BigDecimal.valueOf(W_SAMPLE).setScale(SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(runCount)
                .multiply(BigDecimal.valueOf(W_SAMPLE))
                .divide(BigDecimal.TEN, SCALE, RoundingMode.HALF_UP);
    }

    static String gradeOf(int score) {
        if (score >= 90) {
            return "A";
        }
        if (score >= 80) {
            return "B";
        }
        if (score >= 70) {
            return "C";
        }
        if (score >= 60) {
            return "D";
        }
        return "E";
    }

    /** value 在 [lo,hi] 线性映射到 [0,maxPoints]。 */
    private static BigDecimal linearClamp(BigDecimal value, BigDecimal lo, BigDecimal hi, int maxPoints) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        if (value.compareTo(lo) <= 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        if (value.compareTo(hi) >= 0) {
            return BigDecimal.valueOf(maxPoints).setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal span = hi.subtract(lo);
        if (span.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return value.subtract(lo)
                .multiply(BigDecimal.valueOf(maxPoints))
                .divide(span, SCALE, RoundingMode.HALF_UP);
    }

    /** value 在 [lo,hi] 越大分越低：lo→满分，hi→0。 */
    private static BigDecimal linearClampInverse(BigDecimal value, BigDecimal lo, BigDecimal hi, int maxPoints) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        if (value.compareTo(lo) <= 0) {
            return BigDecimal.valueOf(maxPoints).setScale(SCALE, RoundingMode.HALF_UP);
        }
        if (value.compareTo(hi) >= 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal span = hi.subtract(lo);
        if (span.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.valueOf(maxPoints).setScale(SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(maxPoints)
                .subtract(value.subtract(lo)
                        .multiply(BigDecimal.valueOf(maxPoints))
                        .divide(span, SCALE, RoundingMode.HALF_UP))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> component(String key, String label,
                                                 BigDecimal points, int max,
                                                 String detail) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("label", label);
        m.put("points", points);
        m.put("max", max);
        m.put("detail", detail);
        return m;
    }

    private static String pctLabel(BigDecimal v) {
        if (v == null) {
            return "—";
        }
        return v.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}
