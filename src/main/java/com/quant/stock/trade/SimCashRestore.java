package com.quant.stock.trade;

import java.math.BigDecimal;

/**
 * 模拟现金启动恢复：库中有值（含 0）则采用，否则保留默认。
 */
public final class SimCashRestore {

    private SimCashRestore() {
    }

    public static BigDecimal apply(BigDecimal loadedOrNull, BigDecimal defaultCash) {
        if (loadedOrNull != null) {
            return loadedOrNull;
        }
        return defaultCash == null ? BigDecimal.ZERO : defaultCash;
    }
}
