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
    public static final BigDecimal RATE_BEFORE = new BigDecimal("0.001");
    public static final BigDecimal RATE_AFTER = new BigDecimal("0.0005");

    private StampTaxAsOf() {
    }

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
