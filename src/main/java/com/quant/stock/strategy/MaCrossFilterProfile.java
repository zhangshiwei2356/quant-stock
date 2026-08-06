package com.quant.stock.strategy;

import java.math.BigDecimal;

/**
 * 金叉策略过滤参数画像（与全局 {@code quant.*} 解耦，供对照回测副本使用）。
 * <p>
 * 原版 {@link MaCrossStrategy} 仍读 {@code QuantProperties}，本画像不改其行为。
 * 已下线不成功的 Trend / Volume / Strict 画像；仅保留 Balanced。
 */
public final class MaCrossFilterProfile {

    private final String id;
    private final String label;
    private final String summary;
    private final boolean trendFilterEnabled;
    private final boolean volumeFilterEnabled;
    private final BigDecimal volumeConfirmRatio;
    private final boolean adxFilterEnabled;
    private final BigDecimal adxMin;
    private final BigDecimal adxChopMax;
    private final BigDecimal rsiBuyMax;
    private final BigDecimal atrMinThreshold;

    private MaCrossFilterProfile(String id, String label, String summary,
                                 boolean trendFilterEnabled,
                                 boolean volumeFilterEnabled, BigDecimal volumeConfirmRatio,
                                 boolean adxFilterEnabled, BigDecimal adxMin, BigDecimal adxChopMax,
                                 BigDecimal rsiBuyMax, BigDecimal atrMinThreshold) {
        this.id = id;
        this.label = label;
        this.summary = summary;
        this.trendFilterEnabled = trendFilterEnabled;
        this.volumeFilterEnabled = volumeFilterEnabled;
        this.volumeConfirmRatio = volumeConfirmRatio;
        this.adxFilterEnabled = adxFilterEnabled;
        this.adxMin = adxMin;
        this.adxChopMax = adxChopMax;
        this.rsiBuyMax = rsiBuyMax;
        this.atrMinThreshold = atrMinThreshold;
    }

    /** 趋势 + 放量，RSI 略严。 */
    public static final MaCrossFilterProfile BALANCED = new MaCrossFilterProfile(
            "maCrossBalanced",
            "金叉·均衡过滤（maCrossBalanced）",
            "MA60向上+放量1.2；ADX关；RSI<55",
            true,
            true, new BigDecimal("1.2"),
            false, new BigDecimal("25"), new BigDecimal("20"),
            new BigDecimal("55"), new BigDecimal("0.001"));

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getSummary() {
        return summary;
    }

    public boolean isTrendFilterEnabled() {
        return trendFilterEnabled;
    }

    public boolean isVolumeFilterEnabled() {
        return volumeFilterEnabled;
    }

    public BigDecimal getVolumeConfirmRatio() {
        return volumeConfirmRatio;
    }

    public boolean isAdxFilterEnabled() {
        return adxFilterEnabled;
    }

    public BigDecimal getAdxMin() {
        return adxMin;
    }

    public BigDecimal getAdxChopMax() {
        return adxChopMax;
    }

    public BigDecimal getRsiBuyMax() {
        return rsiBuyMax;
    }

    public BigDecimal getAtrMinThreshold() {
        return atrMinThreshold;
    }
}
