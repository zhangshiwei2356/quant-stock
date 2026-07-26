package com.quant.stock.risk;

/**
 * 跌停挂卖失败计数与强平阈值（单股/组合/实盘共用）。
 */
public final class LimitDownForcePolicy {

    public static final int FORCE_DAYS = 3;

    private LimitDownForcePolicy() {
    }

    /** 已达强平阈值（含等于）。 */
    public static boolean forceSell(int failDays) {
        return failDays >= FORCE_DAYS;
    }

    /** 跌停且未达强平：仅累计失败、暂缓卖出。 */
    public static boolean deferForLimitDown(boolean limitDown, int failDays) {
        return limitDown && !forceSell(failDays);
    }

    /** 非跌停，或已达强平：允许卖出。 */
    public static boolean shouldSellNow(boolean limitDown, int failDays) {
        return !limitDown || forceSell(failDays);
    }
}
