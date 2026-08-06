package com.quant.stock.admin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维可写 / 策略包共用白名单：key → bool|int|decimal。
 */
public final class WritableParamKeys {

    private static final Map<String, String> TYPES;

    static {
        Map<String, String> w = new LinkedHashMap<String, String>();
        w.put("trendFilterEnabled", "bool");
        w.put("trendMaPeriod", "int");
        w.put("volumeFilterEnabled", "bool");
        w.put("volumeConfirmRatio", "decimal");
        w.put("adxFilterEnabled", "bool");
        w.put("adxMin", "decimal");
        w.put("adxChopMax", "decimal");
        w.put("rsiBuyMax", "decimal");
        w.put("maxSinglePosition", "decimal");
        w.put("maxTotalPosition", "decimal");
        w.put("pyramidEnabled", "bool");
        w.put("pyramidFirst", "decimal");
        w.put("pyramidSecond", "decimal");
        w.put("pyramidThird", "decimal");
        w.put("pyramidAddPct", "decimal");
        w.put("stopLossEnabled", "bool");
        w.put("atrStopMultiplier", "decimal");
        w.put("hardStopCapitalPct", "decimal");
        w.put("trailingStopEnabled", "bool");
        w.put("trailingAtrMultiplier", "decimal");
        w.put("maxHoldTradingDays", "int");
        w.put("feeRate", "decimal");
        w.put("slipPoint", "decimal");
        w.put("maxParticipationAdv", "decimal");
        w.put("limitPriceProtectEnabled", "bool");
        w.put("nextBarOpenFill", "bool");
        w.put("poolRebuildRefreshFactors", "bool");
        w.put("poolRebuildFullBacktest", "bool");
        w.put("dailyLossLimitPct", "decimal");
        w.put("consecutiveLossLimit", "int");
        w.put("drawdownReducePct", "decimal");
        w.put("drawdownHaltPct", "decimal");
        TYPES = Collections.unmodifiableMap(w);
    }

    private WritableParamKeys() {
    }

    public static Map<String, String> types() {
        return TYPES;
    }

    public static boolean isWritable(String key) {
        return key != null && TYPES.containsKey(key);
    }

    public static String typeOf(String key) {
        return TYPES.get(key);
    }
}
