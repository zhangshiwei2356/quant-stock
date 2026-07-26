package com.quant.stock.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组合持仓两两收益相关（P0-105）：用日收益 Pearson；告警用均值/最大相关。
 */
public final class PortfolioCorrelationMonitor {

    private PortfolioCorrelationMonitor() {
    }

    /**
     * @param closeByCode 代码 → 按时间升序收盘价（至少 lookback+1 点更佳）
     * @param lookback    收益窗口长度（交易日数）
     */
    public static Map<String, Object> report(Map<String, List<BigDecimal>> closeByCode,
                                             int lookback,
                                             BigDecimal warnThreshold) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        int lb = Math.max(5, lookback);
        BigDecimal warn = warnThreshold == null ? new BigDecimal("0.75") : warnThreshold;

        List<String> codes = new ArrayList<String>();
        List<double[]> rets = new ArrayList<double[]>();
        if (closeByCode != null) {
            for (Map.Entry<String, List<BigDecimal>> e : closeByCode.entrySet()) {
                double[] r = toReturns(e.getValue(), lb);
                if (r != null && r.length >= 5) {
                    codes.add(e.getKey());
                    rets.add(r);
                }
            }
        }
        out.put("symbolCount", codes.size());
        out.put("lookback", lb);
        out.put("warnThreshold", warn);

        if (codes.size() < 2) {
            out.put("pairCount", 0);
            out.put("avgCorrelation", null);
            out.put("maxCorrelation", null);
            out.put("maxPair", null);
            out.put("warn", false);
            out.put("hint", "有效收益序列不足 2 只，跳过相关监控");
            out.put("pairs", new ArrayList<Map<String, Object>>());
            return out;
        }

        int n = alignLength(rets);
        List<Map<String, Object>> pairs = new ArrayList<Map<String, Object>>();
        double sum = 0;
        double max = -2;
        String maxPair = null;
        int pairCount = 0;
        for (int i = 0; i < codes.size(); i++) {
            for (int j = i + 1; j < codes.size(); j++) {
                double c = pearson(rets.get(i), rets.get(j), n);
                if (Double.isNaN(c)) {
                    continue;
                }
                pairCount++;
                sum += c;
                if (c > max) {
                    max = c;
                    maxPair = codes.get(i) + "-" + codes.get(j);
                }
                Map<String, Object> p = new LinkedHashMap<String, Object>();
                p.put("a", codes.get(i));
                p.put("b", codes.get(j));
                p.put("corr", BigDecimal.valueOf(c).setScale(4, RoundingMode.HALF_UP));
                pairs.add(p);
            }
        }
        BigDecimal avg = pairCount == 0 ? null
                : BigDecimal.valueOf(sum / pairCount).setScale(4, RoundingMode.HALF_UP);
        BigDecimal maxBd = pairCount == 0 || max < -1 ? null
                : BigDecimal.valueOf(max).setScale(4, RoundingMode.HALF_UP);
        boolean warnFlag = avg != null && avg.compareTo(warn) >= 0;
        out.put("pairCount", pairCount);
        out.put("avgCorrelation", avg);
        out.put("maxCorrelation", maxBd);
        out.put("maxPair", maxPair);
        out.put("warn", warnFlag);
        out.put("hint", warnFlag
                ? "平均两两相关≥阈值，组合分散失效风险升高（只告警不改金叉）"
                : "相关在阈值内或样本不足");
        out.put("pairs", pairs);
        return out;
    }

    private static double[] toReturns(List<BigDecimal> closes, int lookback) {
        if (closes == null || closes.size() < 2) {
            return null;
        }
        int from = Math.max(1, closes.size() - lookback);
        List<Double> list = new ArrayList<Double>();
        for (int i = from; i < closes.size(); i++) {
            BigDecimal a = closes.get(i - 1);
            BigDecimal b = closes.get(i);
            if (a == null || b == null || a.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            list.add(b.subtract(a).divide(a, 8, RoundingMode.HALF_UP).doubleValue());
        }
        if (list.size() < 5) {
            return null;
        }
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private static int alignLength(List<double[]> rets) {
        int n = Integer.MAX_VALUE;
        for (double[] r : rets) {
            n = Math.min(n, r.length);
        }
        return n;
    }

    private static double pearson(double[] a, double[] b, int n) {
        if (n < 5) {
            return Double.NaN;
        }
        double sa = 0, sb = 0;
        for (int i = 0; i < n; i++) {
            sa += a[a.length - n + i];
            sb += b[b.length - n + i];
        }
        double ma = sa / n;
        double mb = sb / n;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < n; i++) {
            double xa = a[a.length - n + i] - ma;
            double xb = b[b.length - n + i] - mb;
            num += xa * xb;
            da += xa * xa;
            db += xb * xb;
        }
        if (da <= 1e-18 || db <= 1e-18) {
            return Double.NaN;
        }
        return num / Math.sqrt(da * db);
    }
}
