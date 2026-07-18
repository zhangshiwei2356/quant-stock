package com.quant.stock.util;

import com.quant.stock.config.QuantProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A股仓位手数：单只上限 × ATR调节 × 金字塔分批 × 100股整数手
 */
@Component
@RequiredArgsConstructor
public class PositionAmountUtil {

    private final QuantProperties quantProperties;

    public int calcBuyVolume(BigDecimal totalCash, BigDecimal price, BigDecimal currentAtr) {
        return calcBuyVolume(totalCash, price, currentAtr, BigDecimal.ONE);
    }

    public int calcBuyVolume(BigDecimal totalCash, BigDecimal price, BigDecimal currentAtr, BigDecimal scale) {
        if (totalCash == null || price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        BigDecimal s = scale == null ? BigDecimal.ONE : scale;
        BigDecimal maxSingleMoney = totalCash.multiply(quantProperties.getMaxSinglePosition()).multiply(s);
        BigDecimal atr = currentAtr == null || currentAtr.compareTo(BigDecimal.ZERO) <= 0
                ? quantProperties.getBaseAtr() : currentAtr;
        BigDecimal atrRate = quantProperties.getBaseAtr().divide(atr, 8, RoundingMode.HALF_UP);
        if (atrRate.compareTo(new BigDecimal("1.5")) > 0) {
            atrRate = new BigDecimal("1.5");
        }
        if (atrRate.compareTo(new BigDecimal("0.2")) < 0) {
            atrRate = new BigDecimal("0.2");
        }
        BigDecimal realMoney = maxSingleMoney.multiply(atrRate);
        int shareCount = realMoney.divide(price, 0, RoundingMode.DOWN).intValue();
        return (shareCount / 100) * 100;
    }

    /** 金字塔第 stage 批（0/1/2）目标手数 */
    public int pyramidSlice(int fullVolume, int stage) {
        if (fullVolume < 100) {
            return 0;
        }
        BigDecimal pct;
        if (stage == 0) {
            pct = quantProperties.getPyramidFirst();
        } else if (stage == 1) {
            pct = quantProperties.getPyramidSecond();
        } else {
            pct = quantProperties.getPyramidThird();
        }
        int vol = BigDecimal.valueOf(fullVolume).multiply(pct).intValue();
        vol = (vol / 100) * 100;
        if (vol < 100 && fullVolume >= 100 && stage == 0) {
            return 100;
        }
        return vol;
    }

    public boolean withinTotalPosition(BigDecimal totalCash, BigDecimal positionMarketValue, BigDecimal addMoney) {
        BigDecimal max = totalCash.multiply(quantProperties.getMaxTotalPosition());
        return positionMarketValue.add(addMoney).compareTo(max) <= 0;
    }
}
