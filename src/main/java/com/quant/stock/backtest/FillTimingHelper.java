package com.quant.stock.backtest;

import com.quant.stock.market.dto.BarDTO;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 撮合时点：日K/大周期 = 信号次日开盘；分钟序列 = 信号次日 ≥09:45。
 * {@link #isMinuteSeries} 在 index 附近取样，避免隔夜/午休大间隔误判为日K。
 */
public final class FillTimingHelper {

    public static final LocalTime EFFECTIVE_OPEN = LocalTime.of(9, 45);

    private FillTimingHelper() {
    }

    /**
     * 是否分钟序列：在 index 附近寻找间隔落在 (0, 60] 分钟的相邻 bar
     * （避开隔夜/午休大间隔误判为日K）。
     */
    public static boolean isMinuteSeries(List<BarDTO> bars, int index) {
        if (bars == null || bars.size() < 2) {
            return false;
        }
        int from = Math.max(1, index - 8);
        int to = Math.min(bars.size() - 1, Math.max(index, 1) + 8);
        for (int i = from; i <= to; i++) {
            if (bars.get(i) == null || bars.get(i - 1) == null
                    || bars.get(i).getBarBegin() == null
                    || bars.get(i - 1).getBarBegin() == null) {
                continue;
            }
            long minutes = java.time.Duration.between(
                    bars.get(i - 1).getBarBegin(), bars.get(i).getBarBegin()).toMinutes();
            if (minutes > 0 && minutes <= 60) {
                return true;
            }
        }
        return false;
    }

    /**
     * 当前 bar 是否允许成交「次日有效」挂单。
     * 日K：true（本根即下一根开盘）。
     * 分钟K：须 bar 时间 ≥ 09:45。
     */
    public static boolean canFillPendingOnBar(List<BarDTO> bars, int index) {
        if (bars == null || index < 0 || index >= bars.size() || bars.get(index) == null) {
            return false;
        }
        if (!isMinuteSeries(bars, index)) {
            return true;
        }
        LocalDateTime t = bars.get(index).getBarBegin();
        if (t == null) {
            return false;
        }
        LocalTime time = t.toLocalTime();
        return !time.isBefore(EFFECTIVE_OPEN);
    }

    /** 分钟序列开盘静默：09:30–09:45 不可新开成交 */
    public static boolean isOpenQuietMinute(LocalDateTime t) {
        if (t == null) {
            return false;
        }
        LocalTime time = t.toLocalTime();
        return !time.isBefore(LocalTime.of(9, 30)) && time.isBefore(EFFECTIVE_OPEN);
    }
}
