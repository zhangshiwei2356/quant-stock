package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 限价保护（P0-94）：成交价不得突破涨跌停价。
 * 五档/L2 盘口无本地数据，ADV 上限见 {@link com.quant.stock.trade.ParticipationCap}。
 */
public final class LimitPriceProtect {

    private LimitPriceProtect() {
    }

    /** 构建限价保护能力快照（供运维/验收接口）。 */
    public static Map<String, Object> status(QuantProperties props) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", props != null && props.isLimitPriceProtectEnabled());
        m.put("bookClamp", true);
        m.put("fiveLevelBook", "UNAVAILABLE");
        m.put("l2Depth", "UNAVAILABLE");
        m.put("advCap", "ParticipationCap");
        m.put("hint", "买入价≤涨停、卖出价≥跌停；五档/L2 本地不可用；与 ADV 参与率帽配合");
        return m;
    }

    /**
     * 买入成交价上限=涨停价；若无昨收则原样返回。
     *
     * @return clamped 价；若应拒单（可选）由调用方看 {@link #shouldRejectBuy}
     */
    public static BigDecimal clampBuy(BigDecimal deal, BigDecimal prevClose, String code, boolean st) {
        if (deal == null) {
            return null;
        }
        BigDecimal up = LimitBoardHelper.limitUpPrice(prevClose, code, st);
        if (up != null && deal.compareTo(up) > 0) {
            return up;
        }
        return deal;
    }

    /** 卖出成交价下限=跌停价 */
    public static BigDecimal clampSell(BigDecimal deal, BigDecimal prevClose, String code, boolean st) {
        if (deal == null) {
            return null;
        }
        BigDecimal down = LimitBoardHelper.limitDownPrice(prevClose, code, st);
        if (down != null && deal.compareTo(down) < 0) {
            return down;
        }
        return deal;
    }

    /** 基准开盘已涨停则拒买（保护限价无法优于涨停） */
    public static boolean shouldRejectBuy(BigDecimal base, BigDecimal prevClose, String code, boolean st) {
        BigDecimal up = LimitBoardHelper.limitUpPrice(prevClose, code, st);
        return up != null && base != null && base.compareTo(up) >= 0;
    }
}
