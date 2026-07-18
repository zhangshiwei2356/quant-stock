package com.quant.stock.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 回测成交汇总：买卖次数/股数/手数/金额、费用、总盈亏。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackTestTradeStats {

    private int buyCount;
    private int sellCount;
    /** 买入总股数 */
    private int buyShares;
    /** 卖出总股数 */
    private int sellShares;
    /** 买入总手数（股数/100） */
    private int buyLots;
    /** 卖出总手数 */
    private int sellLots;
    /** 买入成交额合计（不含佣金） */
    private BigDecimal buyAmount;
    /** 卖出成交额合计（不含费用） */
    private BigDecimal sellAmount;
    /** 买卖费用合计（佣金+印花税等） */
    private BigDecimal totalFee;
    /** 总盈亏 = 期末资产 − 初始资金 */
    private BigDecimal totalPnl;

    public static BackTestTradeStats from(List<BackTradeRecord> trades,
                                          BigDecimal initCapital,
                                          BigDecimal finalAsset) {
        int buyCount = 0;
        int sellCount = 0;
        int buyShares = 0;
        int sellShares = 0;
        BigDecimal buyAmount = BigDecimal.ZERO;
        BigDecimal sellAmount = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        if (trades != null) {
            for (BackTradeRecord t : trades) {
                if (t == null) {
                    continue;
                }
                int vol = t.getVolume() == null ? 0 : t.getVolume();
                BigDecimal amt = t.getAmount() == null ? BigDecimal.ZERO : t.getAmount();
                BigDecimal fee = t.getFee() == null ? BigDecimal.ZERO : t.getFee();
                totalFee = totalFee.add(fee);
                String side = t.getSide() == null ? "" : t.getSide().trim().toUpperCase();
                if ("BUY".equals(side)) {
                    buyCount++;
                    buyShares += vol;
                    buyAmount = buyAmount.add(amt);
                } else if ("SELL".equals(side)) {
                    sellCount++;
                    sellShares += vol;
                    sellAmount = sellAmount.add(amt);
                }
            }
        }
        BigDecimal init = initCapital == null ? BigDecimal.ZERO : initCapital;
        BigDecimal fin = finalAsset == null ? init : finalAsset;
        return BackTestTradeStats.builder()
                .buyCount(buyCount)
                .sellCount(sellCount)
                .buyShares(buyShares)
                .sellShares(sellShares)
                .buyLots(buyShares / 100)
                .sellLots(sellShares / 100)
                .buyAmount(buyAmount.setScale(2, RoundingMode.HALF_UP))
                .sellAmount(sellAmount.setScale(2, RoundingMode.HALF_UP))
                .totalFee(totalFee.setScale(2, RoundingMode.HALF_UP))
                .totalPnl(fin.subtract(init).setScale(2, RoundingMode.HALF_UP))
                .build();
    }
}
