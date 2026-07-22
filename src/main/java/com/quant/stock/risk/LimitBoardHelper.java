package com.quant.stock.risk;

import com.quant.stock.market.dto.BarDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A股涨跌停幅度与封板判定（相对昨收）。
 * 主板/中小板 ±10%；创业板(3*)、科创板(688) ±20%；ST/&#42;ST ±5%。
 */
public final class LimitBoardHelper {

    private static final BigDecimal MAIN_LIMIT = new BigDecimal("0.10");
    private static final BigDecimal GEM_STAR_LIMIT = new BigDecimal("0.20");
    private static final BigDecimal ST_LIMIT = new BigDecimal("0.05");
    private static final BigDecimal TICK = new BigDecimal("0.01");

    private LimitBoardHelper() {
    }

    /** 涨跌停幅度（正数，如 0.10 / 0.20 / 0.05） */
    public static BigDecimal limitPct(String stockCode) {
        return limitPct(stockCode, false);
    }

    public static BigDecimal limitPct(String stockCode, boolean stStock) {
        if (stStock) {
            return ST_LIMIT;
        }
        if (stockCode == null) {
            return MAIN_LIMIT;
        }
        String c = stockCode.trim();
        if (c.startsWith("688") || c.startsWith("3")) {
            return GEM_STAR_LIMIT;
        }
        return MAIN_LIMIT;
    }

    public static BigDecimal limitUpPrice(BigDecimal prevClose, String stockCode) {
        return limitUpPrice(prevClose, stockCode, false);
    }

    public static BigDecimal limitUpPrice(BigDecimal prevClose, String stockCode, boolean stStock) {
        if (prevClose == null || prevClose.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return prevClose.multiply(BigDecimal.ONE.add(limitPct(stockCode, stStock)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal limitDownPrice(BigDecimal prevClose, String stockCode) {
        return limitDownPrice(prevClose, stockCode, false);
    }

    public static BigDecimal limitDownPrice(BigDecimal prevClose, String stockCode, boolean stStock) {
        if (prevClose == null || prevClose.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return prevClose.multiply(BigDecimal.ONE.subtract(limitPct(stockCode, stStock)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 是否触及涨停：收盘≥昨收×(1+限幅×0.95) 或 OHLC 贴齐涨停价（封板）。
     */
    public static boolean isLimitUp(BarDTO cur, BigDecimal prevClose, String stockCode) {
        return isLimitUp(cur, prevClose, stockCode, false);
    }

    public static boolean isLimitUp(BarDTO cur, BigDecimal prevClose, String stockCode, boolean stStock) {
        return isLimitSide(cur, prevClose, stockCode, true, stStock);
    }

    public static boolean isLimitDown(BarDTO cur, BigDecimal prevClose, String stockCode) {
        return isLimitDown(cur, prevClose, stockCode, false);
    }

    public static boolean isLimitDown(BarDTO cur, BigDecimal prevClose, String stockCode, boolean stStock) {
        return isLimitSide(cur, prevClose, stockCode, false, stStock);
    }

    private static boolean isLimitSide(BarDTO cur, BigDecimal prevClose, String stockCode,
                                       boolean up, boolean stStock) {
        if (cur == null || prevClose == null || prevClose.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        String code = stockCode != null ? stockCode : cur.getCode();
        BigDecimal limitPx = up
                ? limitUpPrice(prevClose, code, stStock)
                : limitDownPrice(prevClose, code, stStock);
        if (limitPx == null) {
            return false;
        }
        BigDecimal close = cur.getClose();
        if (close != null) {
            BigDecimal soft = prevClose.multiply(up
                    ? BigDecimal.ONE.add(limitPct(code, stStock).multiply(new BigDecimal("0.95")))
                    : BigDecimal.ONE.subtract(limitPct(code, stStock).multiply(new BigDecimal("0.95"))))
                    .setScale(4, RoundingMode.HALF_UP);
            if (up && close.compareTo(soft) >= 0) {
                return true;
            }
            if (!up && close.compareTo(soft) <= 0) {
                return true;
            }
        }
        return isLockedAt(cur, limitPx);
    }

    /** 开高低收均贴齐限价（±1分）视为封板 */
    public static boolean isLockedAt(BarDTO cur, BigDecimal limitPx) {
        if (cur == null || limitPx == null) {
            return false;
        }
        return near(cur.getOpen(), limitPx) && near(cur.getHigh(), limitPx)
                && near(cur.getLow(), limitPx) && near(cur.getClose(), limitPx);
    }

    private static boolean near(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return false;
        }
        return a.subtract(b).abs().compareTo(TICK) <= 0;
    }
}
