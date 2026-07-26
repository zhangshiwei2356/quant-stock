package com.quant.stock.trade;

import java.math.BigDecimal;

/**
 * 成交时按当前仓位系数缩量并取整手（与单股回测一致）。
 * 挂单量 × 系数后不足 1 手 → 返回 0（调用方应取消挂单）。
 */
public final class FillVolumeScale {

    private FillVolumeScale() {
    }

    /**
     * @param pendingVol 挂单股数
     * @param posScale   当前仓位系数（含压力/突变等）；null 或 ≤0 视为不可成交
     * @return 取整手后的请求股数；不足 100 返回 0
     */
    public static int scaleToLot(int pendingVol, BigDecimal posScale) {
        if (pendingVol < 100 || posScale == null || posScale.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        int vol = BigDecimal.valueOf(pendingVol).multiply(posScale).intValue();
        vol = (vol / 100) * 100;
        return vol >= 100 ? vol : 0;
    }
}
