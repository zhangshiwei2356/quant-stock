package com.quant.stock.risk;

import java.math.BigDecimal;

/**
 * 止损/trail 成交基准价（P0-115）：
 * <ul>
 *   <li>开盘价已跌破止损（跳空穿价）→ 按<strong>开盘价</strong>成交（不利）</li>
 *   <li>否则盘中最低触及止损 → 按<strong>止损价</strong>成交</li>
 * </ul>
 * 不含滑点/冲击（由 TradeCostModel 再套）。
 */
public final class StopFillPrice {

    public enum Mode {
        /** 未触及 */
        NONE,
        /** 跳空穿价：open ≤ stop */
        GAP_THROUGH,
        /** 盘中触及：low ≤ stop &lt; open */
        INTRADAY_TOUCH
    }

    public static final class Result {
        public final Mode mode;
        public final BigDecimal fillBase;

        public Result(Mode mode, BigDecimal fillBase) {
            this.mode = mode;
            this.fillBase = fillBase;
        }

        public boolean triggered() {
            return mode != Mode.NONE && fillBase != null;
        }
    }

    private StopFillPrice() {
    }

    public static Result resolve(BigDecimal open, BigDecimal low, BigDecimal stop) {
        if (stop == null || stop.compareTo(BigDecimal.ZERO) <= 0 || low == null) {
            return new Result(Mode.NONE, null);
        }
        if (low.compareTo(stop) > 0) {
            return new Result(Mode.NONE, null);
        }
        if (open != null && open.compareTo(stop) <= 0) {
            return new Result(Mode.GAP_THROUGH, open);
        }
        return new Result(Mode.INTRADAY_TOUCH, stop);
    }
}
