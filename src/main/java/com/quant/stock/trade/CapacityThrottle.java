package com.quant.stock.trade;

import com.quant.stock.config.QuantProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 容量/扩容降频（P0-112）：AUM 放大时收紧 ADV 参与率；可选按当根量 POV 切片。
 * 只降仓/降频，不改金叉。无真 TWAP 时间切片器。
 */
public final class CapacityThrottle {

    private CapacityThrottle() {
    }

    public static Map<String, Object> status(QuantProperties props, BigDecimal equity) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        BigDecimal cfg = props == null ? null : props.getMaxParticipationAdv();
        BigDecimal base = props == null ? null : props.getCapacityAumBase();
        BigDecimal pov = props == null ? null : props.getPovMaxBarVolumePct();
        BigDecimal eff = effectiveMaxParticipation(cfg, equity, base);
        m.put("configuredMaxParticipationAdv", cfg);
        m.put("capacityAumBase", base);
        m.put("equity", equity);
        m.put("effectiveMaxParticipationAdv", eff);
        m.put("povMaxBarVolumePct", pov);
        m.put("twapSlicer", "UNAVAILABLE");
        m.put("hint", "AUM tightens ADV + bar POV; TWAP UNAVAILABLE; throttle only, no gold-cross change");
        return m;
    }

    /**
     * 有效 ADV 参与率硬顶：权益超过基准时按 {@code aumBase/equity} 缩放配置顶。
     */
    public static BigDecimal effectiveMaxParticipation(BigDecimal configuredMax,
                                                      BigDecimal equity,
                                                      BigDecimal aumBase) {
        if (configuredMax == null || configuredMax.compareTo(BigDecimal.ZERO) <= 0) {
            return configuredMax;
        }
        if (aumBase == null || aumBase.compareTo(BigDecimal.ZERO) <= 0
                || equity == null || equity.compareTo(BigDecimal.ZERO) <= 0) {
            return configuredMax;
        }
        if (equity.compareTo(aumBase) <= 0) {
            return configuredMax;
        }
        BigDecimal scale = aumBase.divide(equity, 6, RoundingMode.HALF_UP);
        BigDecimal eff = configuredMax.multiply(scale).setScale(6, RoundingMode.HALF_UP);
        // 不低于配置顶的 10%，避免缩到不可交易
        BigDecimal floor = configuredMax.multiply(new BigDecimal("0.10"));
        return eff.compareTo(floor) < 0 ? floor : eff;
    }

    /**
     * POV 切片：单笔不超过当根成交量 × povPct（整手）。
     *
     * @param povPct ≤0 或 null 关闭
     */
    public static int povCapVolume(int volume, long barVolume, BigDecimal povPct) {
        if (volume < 100 || povPct == null || povPct.compareTo(BigDecimal.ZERO) <= 0) {
            return (volume / 100) * 100;
        }
        return ParticipationCap.capVolume(volume, Math.max(0L, barVolume), povPct);
    }
}
