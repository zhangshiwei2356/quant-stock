package com.quant.stock.kuangrui;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 宽睿价额换算：柜台常用「1 元 = 10000」毫级整数；业务库一律存「元」。
 */
public final class KuangruiPriceScale {

    /** 毫 → 元 */
    public static final BigDecimal SCALE = new BigDecimal("10000");

    private KuangruiPriceScale() {
    }

    /** 毫级价格/金额 → 元；≤0 或非法返回 null。 */
    public static BigDecimal toYuan(long milli) {
        if (milli <= 0L) {
            return null;
        }
        return BigDecimal.valueOf(milli).divide(SCALE, 4, RoundingMode.HALF_UP);
    }

    /** 可空包装：null / ≤0 → null。 */
    public static BigDecimal toYuan(Long milli) {
        if (milli == null || milli.longValue() <= 0L) {
            return null;
        }
        return toYuan(milli.longValue());
    }

    /**
     * 毫级金额 → 元（允许 0，用于资金余额；负值按原样换算）。
     */
    public static BigDecimal toYuanAllowZero(long milli) {
        return BigDecimal.valueOf(milli).divide(SCALE, 4, RoundingMode.HALF_UP);
    }

    /** null → {@code BigDecimal.ZERO}。 */
    public static BigDecimal toYuanAllowZero(Long milli) {
        if (milli == null) {
            return BigDecimal.ZERO;
        }
        return toYuanAllowZero(milli.longValue());
    }
}
