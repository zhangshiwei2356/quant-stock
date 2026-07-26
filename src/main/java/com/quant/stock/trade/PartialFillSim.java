package com.quant.stock.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 回测部成模拟（P0-95）：按成交比例截断到整手，剩余挂单保留。
 * {@code fillRatio ≥ 1} 或 null → 满额；≤0 → 0。
 */
public final class PartialFillSim {

    private PartialFillSim() {
    }

    /**
     * @param requestVol 拟成交股数（已整手）
     * @param fillRatio  本 bar 成交比例，默认 1=满额
     * @return 本 bar 实际可成交整手股数
     */
    public static int fillVolume(int requestVol, BigDecimal fillRatio) {
        if (requestVol < 100) {
            return 0;
        }
        int req = (requestVol / 100) * 100;
        if (fillRatio == null || fillRatio.compareTo(BigDecimal.ONE) >= 0) {
            return req;
        }
        if (fillRatio.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        int filled = BigDecimal.valueOf(req)
                .multiply(fillRatio)
                .setScale(0, RoundingMode.DOWN)
                .intValue();
        filled = (filled / 100) * 100;
        return Math.max(0, Math.min(filled, req));
    }

    public static int remainder(int requestVol, int filledVol) {
        int rem = ((requestVol / 100) * 100) - ((filledVol / 100) * 100);
        return rem >= 100 ? rem : 0;
    }
}
