package com.quant.stock.session;

import com.quant.stock.market.dto.BarDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 会话分钟轴锚点：昨收、当日开盘、高开幅度（供隔日高开等策略使用）。
 */
public final class SessionBarAnchors {

    private SessionBarAnchors() {
    }

    public static BigDecimal prevClose(List<BarDTO> bars, int index) {
        if (bars == null || index <= 0 || index >= bars.size()) {
            return null;
        }
        BarDTO cur = bars.get(index);
        if (cur == null || cur.getBarBegin() == null) {
            return null;
        }
        LocalDate day = cur.getBarBegin().toLocalDate();
        for (int i = index - 1; i >= 0; i--) {
            BarDTO b = bars.get(i);
            if (b == null || b.getBarBegin() == null || b.getClose() == null) {
                continue;
            }
            if (b.getBarBegin().toLocalDate().isBefore(day)) {
                return b.getClose();
            }
        }
        return null;
    }

    /** 当日第一根交易分钟的开盘价。 */
    public static BigDecimal dayOpen(List<BarDTO> bars, int index) {
        if (bars == null || index < 0 || index >= bars.size()) {
            return null;
        }
        BarDTO cur = bars.get(index);
        if (cur == null || cur.getBarBegin() == null) {
            return null;
        }
        LocalDate day = cur.getBarBegin().toLocalDate();
        BigDecimal first = null;
        for (int i = 0; i <= index; i++) {
            BarDTO b = bars.get(i);
            if (b == null || b.getBarBegin() == null || b.getOpen() == null) {
                continue;
            }
            if (!b.getBarBegin().toLocalDate().equals(day)) {
                continue;
            }
            LocalTime t = b.getBarBegin().toLocalTime();
            if (!SessionTradingMinutes.isTradingMinute(t)) {
                continue;
            }
            first = b.getOpen();
            break;
        }
        return first;
    }

    /** 开盘相对昨收：dayOpen/prevClose - 1；缺数返回 null。 */
    public static BigDecimal gapPct(BigDecimal dayOpen, BigDecimal prevClose) {
        if (dayOpen == null || prevClose == null || prevClose.signum() <= 0) {
            return null;
        }
        return dayOpen.divide(prevClose, 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
    }

    /** 当前价相对昨收：close/prevClose - 1。 */
    public static BigDecimal dayRet(BigDecimal close, BigDecimal prevClose) {
        if (close == null || prevClose == null || prevClose.signum() <= 0) {
            return null;
        }
        return close.divide(prevClose, 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
    }

    public static void applyTo(SessionContext.SessionContextBuilder builder, List<BarDTO> bars, int index) {
        if (builder == null) {
            return;
        }
        BigDecimal pc = prevClose(bars, index);
        BigDecimal open = dayOpen(bars, index);
        builder.prevClose(pc).dayOpen(open).gapPct(gapPct(open, pc));
        if (bars != null && index >= 0 && index < bars.size() && bars.get(index) != null) {
            builder.dayRet(dayRet(bars.get(index).getClose(), pc));
        }
    }
}
