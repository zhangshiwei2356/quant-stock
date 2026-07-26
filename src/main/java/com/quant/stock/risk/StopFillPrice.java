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

    /** 止损触发与成交基准价判定模式 */
    public enum Mode {
        /** 未触及 */
        NONE,
        /** 跳空穿价：open ≤ stop */
        GAP_THROUGH,
        /** 盘中触及：low ≤ stop &lt; open */
        INTRADAY_TOUCH
    }

    /** 止损价解析结果 */
    public static final class Result {
        /** 触发模式 */
        public final Mode mode;
        /** 未套滑点前的成交基准价 */
        public final BigDecimal fillBase;

        public Result(Mode mode, BigDecimal fillBase) {
            this.mode = mode;
            this.fillBase = fillBase;
        }

        /** 是否已触发止损（含跳空与盘中触及） */
        public boolean triggered() {
            return mode != Mode.NONE && fillBase != null;
        }
    }

    private StopFillPrice() {
    }

    /**
     * 根据开高低与止损价判定是否触发及成交基准价。
     *
     * @param open  开盘价
     * @param low   最低价
     * @param stop  有效止损价
     */
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
