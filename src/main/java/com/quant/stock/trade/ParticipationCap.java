package com.quant.stock.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 单笔成交量相对 20 日均量（ADV）的参与率硬顶（P0-89）。
 * {@code maxParticipationAdv ≤ 0} 表示关闭。
 */
public final class ParticipationCap {

    private ParticipationCap() {
    }

    /**
     * 将目标股数向下取整到不超过 {@code ADV × maxParticipation} 的整手（100 股）。
     *
     * @return 受限后的股数；不足 1 手返回 0
     */
    public static int capVolume(int volume, long adv20, BigDecimal maxParticipationAdv) {
        if (volume < 100) {
            return 0;
        }
        if (maxParticipationAdv == null || maxParticipationAdv.compareTo(BigDecimal.ZERO) <= 0) {
            return (volume / 100) * 100;
        }
        long adv = Math.max(0L, adv20);
        long maxShares = BigDecimal.valueOf(adv)
                .multiply(maxParticipationAdv)
                .setScale(0, RoundingMode.DOWN)
                .longValue();
        if (maxShares < 100L) {
            return 0;
        }
        int capped = (int) Math.min((long) volume, (maxShares / 100L) * 100L);
        return (capped / 100) * 100;
    }
}
