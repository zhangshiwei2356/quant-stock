package com.quant.stock.kuangrui;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 宽睿行情头时间 → 1 分钟 bar 起始时刻（向下取整到分钟）。
 */
public final class MdsBarTimeUtil {

    private MdsBarTimeUtil() {
    }

    /**
     * @param tradeDate YYYYMMDD
     * @param updateTime HHMMSS 或 HHMMSSsss
     */
    public static LocalDateTime barBegin(int tradeDate, int updateTime) {
        if (tradeDate < 19700101 || tradeDate > 29991231) {
            return null;
        }
        int y = tradeDate / 10000;
        int mo = (tradeDate / 100) % 100;
        int d = tradeDate % 100;
        if (mo < 1 || mo > 12 || d < 1 || d > 31) {
            return null;
        }
        int t = updateTime;
        if (t < 0) {
            t = 0;
        }
        // HHMMSSsss（≥ 1e7）去掉毫秒
        if (t >= 10000000) {
            t = t / 1000;
        }
        int h = t / 10000;
        int mi = (t / 100) % 100;
        int s = t % 100;
        if (h > 23 || mi > 59 || s > 59) {
            return null;
        }
        return LocalDateTime.of(y, mo, d, h, mi, s).truncatedTo(ChronoUnit.MINUTES);
    }
}
