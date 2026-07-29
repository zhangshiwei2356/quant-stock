package com.quant.stock.strategy;

import java.math.BigDecimal;

/**
 * 金叉策略过滤参数画像（与全局 {@code quant.*} 解耦，供对照回测副本使用）。
 * <p>
 * 原版 {@link MaCrossStrategy} 仍读 {@code QuantProperties}，本画像不改其行为。
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

    /** 仅开 MA60 趋势过滤（对照「裸金叉」）。 */
    public static final MaCrossFilterProfile TREND = new MaCrossFilterProfile(
            "maCrossTrend",
            "金叉·趋势过滤（maCrossTrend）",
            "MA60向上；放量/ADX关；RSI<60",
            true,
            false, new BigDecimal("1.2"),
            false, new BigDecimal("25"), new BigDecimal("20"),
            new BigDecimal("60"), new BigDecimal("0.001"));

    /** 仅开放量确认，倍数略严。 */
    public static final MaCrossFilterProfile VOLUME = new MaCrossFilterProfile(
            "maCrossVolume",
            "金叉·放量确认（maCrossVolume）",
            "放量≥1.5×均量；趋势/ADX关；RSI<60",
            false,
            true, new BigDecimal("1.5"),
            false, new BigDecimal("25"), new BigDecimal("20"),
            new BigDecimal("60"), new BigDecimal("0.001"));

    /** 趋势 + 放量，RSI 略严。 */
    public static final MaCrossFilterProfile BALANCED = new MaCrossFilterProfile(
            "maCrossBalanced",
            "金叉·均衡过滤（maCrossBalanced）",
            "MA60向上+放量1.2；ADX关；RSI<55",
            true,
            true, new BigDecimal("1.2"),
            false, new BigDecimal("25"), new BigDecimal("20"),
            new BigDecimal("55"), new BigDecimal("0.001"));

    /** 全开过滤 + 更严 RSI。 */
    public static final MaCrossFilterProfile STRICT = new MaCrossFilterProfile(
            "maCrossStrict",
            "金叉·严格过滤（maCrossStrict）",
            "MA60+放量1.2+ADX；RSI<50",
            true,
            true, new BigDecimal("1.2"),
            true, new BigDecimal("25"), new BigDecimal("20"),
            new BigDecimal("50"), new BigDecimal("0.001"));

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
