package com.quant.stock.trade;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A 股印花税 as-of（P0-104）：卖出单边；2023-08-28 起由千一调至万一（0.05%）。
 * 配置 {@code quant.stamp-tax-rate} 非空且 &gt;0 时作为「无日期」回退覆盖。
 */
public final class StampTaxAsOf {

    /** 政策生效日（含） */
    public static final LocalDate CUT_20230828 = LocalDate.of(2023, 8, 28);
    /** 2023-08-28 前卖出印花税率（千一） */
    public static final BigDecimal RATE_BEFORE = new BigDecimal("0.001");
    /** 2023-08-28 起卖出印花税率（万五） */
    public static final BigDecimal RATE_AFTER = new BigDecimal("0.0005");

    private StampTaxAsOf() {
    }

    /**
     * 按成交日解析卖出印花税率；无日期时可回退配置覆盖。
     *
     * @param tradeDay       成交日；null 时用配置或现行默认率
     * @param configOverride {@code quant.stamp-tax-rate}；仅 tradeDay 为 null 时生效
     * @return 卖出单边印花税率
     */
    public static BigDecimal rateOn(LocalDate tradeDay, BigDecimal configOverride) {
        if (tradeDay == null) {
            return configOverride != null && configOverride.compareTo(BigDecimal.ZERO) > 0
                    ? configOverride : RATE_AFTER;
        }
        if (!tradeDay.isBefore(CUT_20230828)) {
            return RATE_AFTER;
        }
        return RATE_BEFORE;
    }
}
