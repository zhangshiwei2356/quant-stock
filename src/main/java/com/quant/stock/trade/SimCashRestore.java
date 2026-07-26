package com.quant.stock.trade;

import java.math.BigDecimal;

/**
 * 模拟现金启动恢复：库中有值（含 0）则采用，否则保留默认。
 */
public final class SimCashRestore {

    private SimCashRestore() {
    }

    /**
     * 启动时合并库中现金与默认初始资金。
     *
     * @param loadedOrNull 从库读取的现金；null 表示未配置
     * @param defaultCash  应用默认初始资金
     * @return 实际采用的模拟现金
     */
    public static BigDecimal apply(BigDecimal loadedOrNull, BigDecimal defaultCash) {
        if (loadedOrNull != null) {
            return loadedOrNull;
        }
        return defaultCash == null ? BigDecimal.ZERO : defaultCash;
    }
}
