package com.quant.stock.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 单只回测持仓：分档记录买入日，T+1 仅可卖 openDate &lt; 今日 的老仓；加权成本含买入佣金。
 */
public class PositionState {

    private static final class Lot {
        final LocalDate openDate;
        int shares;
        /** 该档累计买入成本（成交额+佣金） */
        BigDecimal cost;

        Lot(LocalDate openDate, int shares, BigDecimal cost) {
            this.openDate = openDate;
            this.shares = shares;
            this.cost = cost;
        }
    }

    private final List<Lot> lots = new ArrayList<Lot>();
    private int shares;
    private BigDecimal totalBuyCost = BigDecimal.ZERO;
    private BigDecimal avgCost = BigDecimal.ZERO;
    private LocalDate lastBuyDate;
    private BigDecimal stopPrice = BigDecimal.ZERO;
    private BigDecimal highestSinceEntry = BigDecimal.ZERO;
    private boolean addedToday;

    public int getShares() {
        return shares;
    }

    public boolean hasPosition() {
        return shares > 0;
    }

    public BigDecimal getAvgCost() {
        return avgCost;
    }

    public LocalDate getLastBuyDate() {
        return lastBuyDate;
    }

    public BigDecimal getStopPrice() {
        return stopPrice;
    }

    public void setStopPrice(BigDecimal stopPrice) {
        this.stopPrice = stopPrice == null ? BigDecimal.ZERO : stopPrice;
    }

    public BigDecimal getHighestSinceEntry() {
        return highestSinceEntry;
    }

    public boolean isAddedToday() {
        return addedToday;
    }

    public void clearAddedToday() {
        this.addedToday = false;
    }

    /** 可止损卖出的老仓股数（openDate &lt; today） */
    public int sellableShares(LocalDate today) {
        if (today == null || shares <= 0) {
            return 0;
        }
        int n = 0;
        for (Lot lot : lots) {
            if (lot.openDate != null && lot.openDate.isBefore(today)) {
                n += lot.shares;
            }
        }
        return n;
    }

    /** 存在可止损老仓 */
    public boolean canSellStops(LocalDate today) {
        return sellableShares(today) > 0;
    }

    public void updateHighest(BigDecimal high) {
        if (high == null || shares <= 0) {
            return;
        }
        if (highestSinceEntry.compareTo(BigDecimal.ZERO) == 0 || high.compareTo(highestSinceEntry) > 0) {
            highestSinceEntry = high;
        }
    }

    /**
     * 买入：同日合并为一档；成本含佣金。
     */
    public void addBuy(int volume, BigDecimal dealPrice, BigDecimal buyFee, LocalDate tradeDay) {
        if (volume <= 0 || dealPrice == null || tradeDay == null) {
            return;
        }
        BigDecimal amount = dealPrice.multiply(BigDecimal.valueOf(volume));
        BigDecimal fee = buyFee == null ? BigDecimal.ZERO : buyFee;
        BigDecimal lotCost = amount.add(fee);
        totalBuyCost = totalBuyCost.add(lotCost);
        shares += volume;
        avgCost = totalBuyCost.divide(BigDecimal.valueOf(shares), 4, RoundingMode.HALF_UP);
        lastBuyDate = tradeDay;
        addedToday = true;
        if (highestSinceEntry.compareTo(BigDecimal.ZERO) == 0) {
            highestSinceEntry = dealPrice;
        }
        for (Lot lot : lots) {
            if (tradeDay.equals(lot.openDate)) {
                lot.shares += volume;
                lot.cost = lot.cost.add(lotCost);
                return;
            }
        }
        lots.add(new Lot(tradeDay, volume, lotCost));
    }

    /**
     * 按 FIFO 优先扣减老仓，卖出 volume 股；返回对应成本（用于盈亏）。
     */
    public BigDecimal removeShares(int volume) {
        if (volume <= 0 || volume > shares) {
            volume = Math.min(Math.max(volume, 0), shares);
        }
        BigDecimal removedCost = BigDecimal.ZERO;
        int left = volume;
        Iterator<Lot> it = lots.iterator();
        while (it.hasNext() && left > 0) {
            Lot lot = it.next();
            int take = Math.min(lot.shares, left);
            BigDecimal partCost = lot.cost.multiply(BigDecimal.valueOf(take))
                    .divide(BigDecimal.valueOf(lot.shares), 6, RoundingMode.HALF_UP);
            removedCost = removedCost.add(partCost);
            lot.shares -= take;
            lot.cost = lot.cost.subtract(partCost);
            left -= take;
            if (lot.shares <= 0) {
                it.remove();
            }
        }
        shares -= volume;
        totalBuyCost = totalBuyCost.subtract(removedCost);
        if (shares <= 0) {
            clear();
        } else {
            avgCost = totalBuyCost.divide(BigDecimal.valueOf(shares), 4, RoundingMode.HALF_UP);
            lastBuyDate = null;
            for (Lot lot : lots) {
                if (lastBuyDate == null || lot.openDate.isAfter(lastBuyDate)) {
                    lastBuyDate = lot.openDate;
                }
            }
        }
        return removedCost;
    }

    public void clear() {
        lots.clear();
        shares = 0;
        totalBuyCost = BigDecimal.ZERO;
        avgCost = BigDecimal.ZERO;
        lastBuyDate = null;
        stopPrice = BigDecimal.ZERO;
        highestSinceEntry = BigDecimal.ZERO;
        addedToday = false;
    }

    /**
     * 从持久化快照恢复（单档近似：T+1 以 entryDate 为准）。
     */
    public void restoreSnapshot(int volume, BigDecimal costAvg, LocalDate entryDate,
                                BigDecimal stop, BigDecimal highest) {
        clear();
        if (volume <= 0 || costAvg == null || costAvg.compareTo(BigDecimal.ZERO) <= 0 || entryDate == null) {
            return;
        }
        this.shares = volume;
        this.avgCost = costAvg.setScale(4, RoundingMode.HALF_UP);
        this.totalBuyCost = costAvg.multiply(BigDecimal.valueOf(volume));
        this.lastBuyDate = entryDate;
        this.stopPrice = stop == null ? BigDecimal.ZERO : stop;
        this.highestSinceEntry = highest == null || highest.compareTo(BigDecimal.ZERO) <= 0 ? costAvg : highest;
        lots.add(new Lot(entryDate, volume, totalBuyCost));
    }

    /** 导出批次（落库用） */
    public List<LotView> snapshotLots() {
        List<LotView> out = new ArrayList<LotView>();
        for (Lot lot : lots) {
            out.add(new LotView(lot.openDate, lot.shares, lot.cost));
        }
        return out;
    }

    /** 从批次恢复（优先于单档快照） */
    public void restoreLots(List<LotView> views, BigDecimal stop, BigDecimal highest) {
        clear();
        if (views == null || views.isEmpty()) {
            return;
        }
        for (LotView v : views) {
            if (v == null || v.shares <= 0 || v.openDate == null || v.cost == null) {
                continue;
            }
            lots.add(new Lot(v.openDate, v.shares, v.cost));
            shares += v.shares;
            totalBuyCost = totalBuyCost.add(v.cost);
            if (lastBuyDate == null || v.openDate.isAfter(lastBuyDate)) {
                lastBuyDate = v.openDate;
            }
        }
        if (shares > 0) {
            avgCost = totalBuyCost.divide(BigDecimal.valueOf(shares), 4, RoundingMode.HALF_UP);
        }
        this.stopPrice = stop == null ? BigDecimal.ZERO : stop;
        this.highestSinceEntry = highest == null || highest.compareTo(BigDecimal.ZERO) <= 0
                ? avgCost : highest;
    }

    /** 持仓批次只读视图 */
    public static final class LotView {
        public final LocalDate openDate;
        public final int shares;
        public final BigDecimal cost;

        public LotView(LocalDate openDate, int shares, BigDecimal cost) {
            this.openDate = openDate;
            this.shares = shares;
            this.cost = cost;
        }
    }

    /**
     * 止损线 = max(成本−2×ATR, 成本−权益×hardPct/股数)，且只上移不下移。
     */
    public void raiseStopByCost(BigDecimal atr, BigDecimal equity, BigDecimal atrMult, BigDecimal hardPct) {
        if (shares <= 0 || avgCost.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal atrV = atr == null ? BigDecimal.ZERO : atr;
        BigDecimal atrStop = avgCost.subtract(atrV.multiply(atrMult == null ? BigDecimal.valueOf(2) : atrMult));
        BigDecimal eq = equity == null || equity.compareTo(BigDecimal.ZERO) <= 0 ? BigDecimal.ZERO : equity;
        BigDecimal hardDist = eq.multiply(hardPct == null ? new BigDecimal("0.02") : hardPct)
                .divide(BigDecimal.valueOf(shares), 4, RoundingMode.HALF_UP);
        BigDecimal hardStop = avgCost.subtract(hardDist);
        BigDecimal candidate = atrStop.max(hardStop);
        if (stopPrice.compareTo(BigDecimal.ZERO) == 0 || candidate.compareTo(stopPrice) > 0) {
            stopPrice = candidate;
        }
    }

    /** 盘后移动止盈：只上移 */
    public void raiseTrailingStop(BigDecimal atr, BigDecimal trailMult) {
        if (shares <= 0 || highestSinceEntry.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal atrV = atr == null ? BigDecimal.ZERO : atr;
        BigDecimal trail = highestSinceEntry.subtract(
                atrV.multiply(trailMult == null ? new BigDecimal("1.5") : trailMult));
        if (stopPrice.compareTo(BigDecimal.ZERO) == 0 || trail.compareTo(stopPrice) > 0) {
            stopPrice = trail;
        }
    }
}
