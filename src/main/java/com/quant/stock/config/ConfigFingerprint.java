package com.quant.stock.config;

import com.quant.stock.risk.ShortSellPolicy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 研究可复现：对策略相关 {@code quant.*} 配置做稳定哈希（P0-93）。
 * <p>
 * 职责：把影响回测成交、仓位与风控的关键配置规范化为固定键序文本，再截取 SHA-256 前 16 个十六进制字符。
 * </p>
 * <p>
 * 关键约束：输出格式 {@code v1:<16位hex>}；{@link #canonical(QuantProperties, String, BigDecimal)} 键序稳定，便于单测与对照实验。
 * </p>
 */
public final class ConfigFingerprint {

    private ConfigFingerprint() {
    }

    /**
     * 使用当前激活策略的指纹名（{@code maCross}→历史 {@code MaCrossStrategy}）与配置内费率计算指纹。
     *
     * @param props 量化配置，可为 null（见 {@link #canonical} 行为）
     * @return {@code v1:} 前缀的 16 位十六进制摘要
     */
    public static String of(QuantProperties props) {
        return of(props, fingerprintStrategyId(props), null);
    }

    /**
     * 将 {@code quant.active-strategy} 映射为指纹字段；金叉默认保持 {@code MaCrossStrategy}。
     */
    public static String fingerprintStrategyId(QuantProperties props) {
        if (props == null || props.getActiveStrategy() == null || props.getActiveStrategy().trim().isEmpty()) {
            return "MaCrossStrategy";
        }
        String id = props.getActiveStrategy().trim();
        if ("maCross".equalsIgnoreCase(id)
                || "MaCrossStrategy".equalsIgnoreCase(id)
                || "MA_CROSS_FILTERED".equalsIgnoreCase(id)) {
            return "MaCrossStrategy";
        }
        return id;
    }

    /**
     * 按指定策略 id 与可选费率覆盖计算配置指纹。
     *
     * @param props            量化配置
     * @param strategyId       写入规范文本的策略标识
     * @param feeRateOverride  非 null 时替代 props 中的 feeRate 参与哈希
     * @return {@code v1:} 前缀的 16 位十六进制摘要
     */
    public static String of(QuantProperties props, String strategyId, BigDecimal feeRateOverride) {
        String canonical = canonical(props, strategyId, feeRateOverride);
        return "v1:" + sha256Hex16(canonical);
    }

    /**
     * 生成用于哈希的规范配置文本（不含摘要前缀后的 hex）。
     *
     * @param p                 量化配置；null 时仅输出策略 id 行
     * @param strategyId        策略标识
     * @param feeRateOverride   非 null 时覆盖 props 中的费率
     * @return {@code v1\\n} 开头、每行 {@code key=value} 的稳定字符串
     */
    public static String canonical(QuantProperties p, String strategyId, BigDecimal feeRateOverride) {
        if (p == null) {
            return "v1\nstrategy=" + nullSafe(strategyId) + "\n";
        }
        BigDecimal fee = feeRateOverride != null ? feeRateOverride : p.getFeeRate();
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("strategy", nullSafe(strategyId));
        m.put("feeRate", dec(fee));
        m.put("maxSinglePosition", dec(p.getMaxSinglePosition()));
        m.put("maxTotalPosition", dec(p.getMaxTotalPosition()));
        m.put("baseAtr", dec(p.getBaseAtr()));
        m.put("atrMinThreshold", dec(p.getAtrMinThreshold()));
        m.put("trendFilterEnabled", bool(p.isTrendFilterEnabled()));
        m.put("trendMaPeriod", String.valueOf(p.getTrendMaPeriod()));
        m.put("volumeFilterEnabled", bool(p.isVolumeFilterEnabled()));
        m.put("volumeConfirmRatio", dec(p.getVolumeConfirmRatio()));
        m.put("adxFilterEnabled", bool(p.isAdxFilterEnabled()));
        m.put("adxMin", dec(p.getAdxMin()));
        m.put("adxChopMax", dec(p.getAdxChopMax()));
        m.put("rsiBuyMax", dec(p.getRsiBuyMax()));
        m.put("stopLossEnabled", bool(p.isStopLossEnabled()));
        m.put("atrStopMultiplier", dec(p.getAtrStopMultiplier()));
        m.put("hardStopCapitalPct", dec(p.getHardStopCapitalPct()));
        m.put("trailingStopEnabled", bool(p.isTrailingStopEnabled()));
        m.put("trailingAtrMultiplier", dec(p.getTrailingAtrMultiplier()));
        m.put("maxHoldTradingDays", String.valueOf(p.getMaxHoldTradingDays()));
        m.put("pyramidEnabled", bool(p.isPyramidEnabled()));
        m.put("pyramidFirst", dec(p.getPyramidFirst()));
        m.put("pyramidSecond", dec(p.getPyramidSecond()));
        m.put("pyramidThird", dec(p.getPyramidThird()));
        m.put("pyramidAddPct", dec(p.getPyramidAddPct()));
        m.put("dailyLossLimitPct", dec(p.getDailyLossLimitPct()));
        m.put("consecutiveLossLimit", String.valueOf(p.getConsecutiveLossLimit()));
        m.put("drawdownReducePct", dec(p.getDrawdownReducePct()));
        m.put("drawdownHaltPct", dec(p.getDrawdownHaltPct()));
        m.put("drawdownDurationReduceDays", String.valueOf(p.getDrawdownDurationReduceDays()));
        m.put("drawdownDurationHaltDays", String.valueOf(p.getDrawdownDurationHaltDays()));
        m.put("autoRetireOnDurationHalt", bool(p.isAutoRetireOnDurationHalt()));
        m.put("retirementCooldownTradingDays", String.valueOf(p.getRetirementCooldownTradingDays()));
        m.put("quietOpenEnabled", bool(p.isQuietOpenEnabled()));
        m.put("quietCloseEnabled", bool(p.isQuietCloseEnabled()));
        m.put("minAvgVolume20", String.valueOf(p.getMinAvgVolume20()));
        m.put("minMarketCapYi", dec(p.getMinMarketCapYi()));
        m.put("marketCapFilterEnabled", bool(p.isMarketCapFilterEnabled()));
        m.put("stampTaxRate", dec(p.getStampTaxRate()));
        m.put("slipLarge", dec(p.getSlipLarge()));
        m.put("slipMid", dec(p.getSlipMid()));
        m.put("slipSmall", dec(p.getSlipSmall()));
        m.put("volLargeThreshold", String.valueOf(p.getVolLargeThreshold()));
        m.put("volMidThreshold", String.valueOf(p.getVolMidThreshold()));
        m.put("impactCoeff", dec(p.getImpactCoeff()));
        m.put("maxParticipationAdv", dec(p.getMaxParticipationAdv()));
        m.put("limitPriceProtectEnabled", bool(p.isLimitPriceProtectEnabled()));
        m.put("softTotalPositionPct", dec(p.getSoftTotalPositionPct()));
        m.put("softSinglePositionPct", dec(p.getSoftSinglePositionPct()));
        m.put("backtestFillRatio", dec(p.getBacktestFillRatio()));
        m.put("stressScenarioEnabled", bool(p.isStressScenarioEnabled()));
        m.put("stressAdvCliffRatio", dec(p.getStressAdvCliffRatio()));
        m.put("signalDriftEnabled", bool(p.isSignalDriftEnabled()));
        m.put("capacityAumBase", dec(p.getCapacityAumBase()));
        m.put("povMaxBarVolumePct", dec(p.getPovMaxBarVolumePct()));
        m.put("dataReconcileGateEnabled", bool(p.isDataReconcileGateEnabled()));
        m.put("structuralBreakEnabled", bool(p.isStructuralBreakEnabled()));
        m.put("stOpenFilterEnabled", bool(p.isStOpenFilterEnabled()));
        m.put("turnoverGuardEnabled", bool(p.isTurnoverGuardEnabled()));
        m.put("turnoverSoftPct", dec(p.getTurnoverSoftPct()));
        m.put("turnoverHardPct", dec(p.getTurnoverHardPct()));
        m.put("icDecayEnabled", bool(p.isIcDecayEnabled()));
        m.put("experimentSeed", nullSafe(p.getExperimentSeed()));
        m.put("allowShort", bool(ShortSellPolicy.allowShort()));
        m.put("nextBarOpenFill", bool(p.isNextBarOpenFill()));
        StringBuilder sb = new StringBuilder("v1\n");
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** SHA-256 取前 8 字节转 hex；异常时返回 16 个 {@code 0} 避免中断调用方。 */
    private static String sha256Hex16(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", dig[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "0000000000000000";
        }
    }

    private static String dec(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }

    private static String bool(boolean v) {
        return v ? "true" : "false";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
