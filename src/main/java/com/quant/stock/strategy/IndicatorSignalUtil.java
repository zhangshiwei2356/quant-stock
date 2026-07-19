package com.quant.stock.strategy;

import com.quant.stock.market.dto.BarDTO;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 TA4J 的指标计算：MA/RSI/ATR/ADX/量能 + 金叉死叉
 */
public final class IndicatorSignalUtil {

    private IndicatorSignalUtil() {
    }

    public static class IndicatorBundle {
        public final double[] ma5;
        public final double[] ma10;
        public final double[] ma20;
        public final double[] ma60;
        public final double[] rsi14;
        public final double[] atr14;
        public final double[] adx14;
        public final double[] volume;
        public final double[] volMa20;
        public final double[] close;
        public final double[] open;
        public final double[] high;
        public final double[] low;
        public final int size;

        public IndicatorBundle(int size) {
            this.size = size;
            this.ma5 = new double[size];
            this.ma10 = new double[size];
            this.ma20 = new double[size];
            this.ma60 = new double[size];
            this.rsi14 = new double[size];
            this.atr14 = new double[size];
            this.adx14 = new double[size];
            this.volume = new double[size];
            this.volMa20 = new double[size];
            this.close = new double[size];
            this.open = new double[size];
            this.high = new double[size];
            this.low = new double[size];
        }

        public boolean isMaCrossUp(int i) {
            if (i < 20) {
                return false;
            }
            return ma5[i] > ma20[i] && !(ma5[i - 1] > ma20[i - 1]);
        }

        public boolean isMaCrossDown(int i) {
            if (i < 20) {
                return false;
            }
            return ma5[i] < ma20[i] && !(ma5[i - 1] < ma20[i - 1]);
        }

        /** 大周期趋势向上：MA60 上行（允许价格略低于MA60，避免漏掉刚启动的趋势） */
        public boolean isTrendUp(int i) {
            if (i < 61 || Double.isNaN(ma60[i]) || Double.isNaN(ma60[i - 1])) {
                return false;
            }
            return ma60[i] >= ma60[i - 1];
        }

        public boolean isVolumeConfirm(int i, double ratio) {
            if (i < 20 || Double.isNaN(volMa20[i]) || volMa20[i] <= 0) {
                return false;
            }
            return volume[i] > volMa20[i] * ratio;
        }

        public boolean isAdxTradable(int i, double adxMin, double chopMax) {
            if (i < 28 || Double.isNaN(adx14[i])) {
                return false;
            }
            if (adx14[i] < chopMax) {
                return false;
            }
            return adx14[i] >= adxMin;
        }
    }

    public static IndicatorBundle precompute(List<BarDTO> bars) {
        int n = bars == null ? 0 : bars.size();
        IndicatorBundle bundle = new IndicatorBundle(n);
        if (n < 20) {
            return bundle;
        }
        BarSeries series = toSeries("pre", bars);
        ClosePriceIndicator closeInd = new ClosePriceIndicator(series);
        VolumeIndicator volInd = new VolumeIndicator(series);
        SMAIndicator ma5 = new SMAIndicator(closeInd, 5);
        SMAIndicator ma10 = new SMAIndicator(closeInd, 10);
        SMAIndicator ma20 = new SMAIndicator(closeInd, 20);
        SMAIndicator ma60 = new SMAIndicator(closeInd, 60);
        SMAIndicator volMa20 = new SMAIndicator(volInd, 20);
        RSIIndicator rsi = new RSIIndicator(closeInd, 14);
        ATRIndicator atr = new ATRIndicator(series, 14);
        ADXIndicator adx = new ADXIndicator(series, 14);
        for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
            BarDTO bar = bars.get(i);
            bundle.close[i] = closeInd.getValue(i).doubleValue();
            bundle.open[i] = bar.getOpen().doubleValue();
            bundle.high[i] = bar.getHigh().doubleValue();
            bundle.low[i] = bar.getLow().doubleValue();
            bundle.volume[i] = bar.getVolume() == null ? 0 : bar.getVolume().doubleValue();
            bundle.ma5[i] = i >= 4 ? ma5.getValue(i).doubleValue() : Double.NaN;
            bundle.ma10[i] = i >= 9 ? ma10.getValue(i).doubleValue() : Double.NaN;
            bundle.ma20[i] = i >= 19 ? ma20.getValue(i).doubleValue() : Double.NaN;
            bundle.ma60[i] = i >= 59 ? ma60.getValue(i).doubleValue() : Double.NaN;
            bundle.rsi14[i] = i >= 14 ? rsi.getValue(i).doubleValue() : Double.NaN;
            bundle.atr14[i] = i >= 14 ? atr.getValue(i).doubleValue() : Double.NaN;
            bundle.adx14[i] = i >= 28 ? adx.getValue(i).doubleValue() : Double.NaN;
            bundle.volMa20[i] = i >= 19 ? volMa20.getValue(i).doubleValue() : Double.NaN;
        }
        return bundle;
    }

    public static long avgVolume(List<BarDTO> bars, int index, int n) {
        if (bars == null || index < 0) {
            return 0L;
        }
        int from = Math.max(0, index - n + 1);
        long sum = 0;
        int cnt = 0;
        for (int i = from; i <= index && i < bars.size(); i++) {
            if (bars.get(i).getVolume() != null) {
                sum += bars.get(i).getVolume().longValue();
                cnt++;
            }
        }
        return cnt == 0 ? 0L : sum / cnt;
    }

    public static BarSeries toSeries(String name, List<BarDTO> bars) {
        BarSeries series = new BaseBarSeries(name);
        for (BarDTO bar : bars) {
            series.addBar(bar.toTa4jBar());
        }
        return series;
    }

    public static Map<String, BigDecimal> calcLatestIndicators(List<BarDTO> bars) {
        Map<String, BigDecimal> map = new HashMap<String, BigDecimal>();
        if (bars == null || bars.size() < 20) {
            return map;
        }
        IndicatorBundle b = precompute(bars);
        int end = b.size - 1;
        map.put("ma5", bd(b.ma5[end]));
        map.put("ma10", bd(b.ma10[end]));
        map.put("ma20", bd(b.ma20[end]));
        map.put("ma60", bd(b.ma60[end]));
        map.put("rsi14", bd(b.rsi14[end]));
        map.put("atr14", bd(b.atr14[end]));
        map.put("adx14", bd(b.adx14[end]));
        map.put("volMa20", bd(b.volMa20[end]));
        map.put("close", bd(b.close[end]));
        // MA60 近 5 日斜率：当前 MA60 相对 5 根前
        if (end >= 64 && !Double.isNaN(b.ma60[end]) && !Double.isNaN(b.ma60[end - 5])) {
            map.put("ma60Prev5", bd(b.ma60[end - 5]));
        }

        BarSeries series = toSeries("ind", bars);
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        BollingerBandsMiddleIndicator mid = new BollingerBandsMiddleIndicator(new SMAIndicator(close, 20));
        StandardDeviationIndicator sd = new StandardDeviationIndicator(close, 20);
        BollingerBandsUpperIndicator upper = new BollingerBandsUpperIndicator(mid, sd);
        BollingerBandsLowerIndicator lower = new BollingerBandsLowerIndicator(mid, sd);
        map.put("bollMid", toBd(mid.getValue(end)));
        map.put("bollUpper", toBd(upper.getValue(end)));
        map.put("bollLower", toBd(lower.getValue(end)));
        return map;
    }

    public static boolean isMaCrossUp(List<BarDTO> bars) {
        if (bars == null || bars.size() < 21) {
            return false;
        }
        return precompute(bars).isMaCrossUp(bars.size() - 1);
    }

    public static boolean isMaCrossDown(List<BarDTO> bars) {
        if (bars == null || bars.size() < 21) {
            return false;
        }
        return precompute(bars).isMaCrossDown(bars.size() - 1);
    }

    public static BigDecimal rsi14(List<BarDTO> bars) {
        Map<String, BigDecimal> m = calcLatestIndicators(bars);
        return m.getOrDefault("rsi14", BigDecimal.ZERO);
    }

    public static BigDecimal atr14(List<BarDTO> bars) {
        Map<String, BigDecimal> m = calcLatestIndicators(bars);
        return m.getOrDefault("atr14", BigDecimal.ZERO);
    }

    public static Map<String, double[]> calcSeriesForChart(List<BarDTO> bars) {
        Map<String, double[]> result = new HashMap<String, double[]>();
        int n = bars == null ? 0 : bars.size();
        double[] ma5Arr = new double[n];
        double[] ma20Arr = new double[n];
        double[] upperArr = new double[n];
        double[] midArr = new double[n];
        double[] lowerArr = new double[n];
        double[] rsiArr = new double[n];
        if (n < 20) {
            result.put("ma5", ma5Arr);
            result.put("ma20", ma20Arr);
            result.put("bollUpper", upperArr);
            result.put("bollMid", midArr);
            result.put("bollLower", lowerArr);
            result.put("rsi14", rsiArr);
            return result;
        }
        IndicatorBundle b = precompute(bars);
        System.arraycopy(b.ma5, 0, ma5Arr, 0, n);
        System.arraycopy(b.ma20, 0, ma20Arr, 0, n);
        System.arraycopy(b.rsi14, 0, rsiArr, 0, n);

        BarSeries series = toSeries("chart", bars);
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        BollingerBandsMiddleIndicator mid = new BollingerBandsMiddleIndicator(new SMAIndicator(close, 20));
        StandardDeviationIndicator sd = new StandardDeviationIndicator(close, 20);
        BollingerBandsUpperIndicator upper = new BollingerBandsUpperIndicator(mid, sd);
        BollingerBandsLowerIndicator lower = new BollingerBandsLowerIndicator(mid, sd);
        for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
            upperArr[i] = i >= 19 ? upper.getValue(i).doubleValue() : Double.NaN;
            midArr[i] = i >= 19 ? mid.getValue(i).doubleValue() : Double.NaN;
            lowerArr[i] = i >= 19 ? lower.getValue(i).doubleValue() : Double.NaN;
        }
        result.put("ma5", ma5Arr);
        result.put("ma20", ma20Arr);
        result.put("bollUpper", upperArr);
        result.put("bollMid", midArr);
        result.put("bollLower", lowerArr);
        result.put("rsi14", rsiArr);
        return result;
    }

    private static BigDecimal bd(double v) {
        if (Double.isNaN(v)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal toBd(Num num) {
        if (num == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(num.doubleValue()).setScale(4, RoundingMode.HALF_UP);
    }
}
