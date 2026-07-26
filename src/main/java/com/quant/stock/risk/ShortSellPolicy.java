package com.quant.stock.risk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 融券/空头边界（P0-102）：本应用为多头现货模拟，禁止开空。
 * 卖出仅允许 ≤ 持仓可卖量（见 {@link RiskControlService#checkSell}）。
 */
public final class ShortSellPolicy {

    private ShortSellPolicy() {
    }

    /** 恒 false；不提供配置开关以免静默打开空头腿。 */
    public static boolean allowShort() {
        return false;
    }

    public static Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("allowShort", allowShort());
        m.put("mode", "LONG_ONLY");
        m.put("hint", "禁融券/空头腿；卖出≤持仓；两融接口族不在范围");
        return m;
    }
}
