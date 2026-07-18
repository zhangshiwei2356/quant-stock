package com.quant.stock.backtest;

import com.quant.stock.backtest.dto.BackTestQueryDTO;
import com.quant.stock.backtest.dto.BackTradeRecord;
import com.quant.stock.backtest.dto.PortfolioResultDTO;
import com.quant.stock.backtest.dto.SingleStockBackResult;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.BarPeriod;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.risk.AccountRiskState;
import com.quant.stock.risk.LimitBoardHelper;
import com.quant.stock.risk.OpenFilterService;
import com.quant.stock.strategy.IndicatorSignalUtil;
import com.quant.stock.strategy.MaCrossStrategy;
import com.quant.stock.trade.TradeCostModel;
import com.quant.stock.util.PositionAmountUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 多标的共享资金池组合回测：对齐单股引擎核心
 * （次日开盘撮合、TradeCostModel、OpenFilter、金字塔、ATR/trail 止损、账户熔断、T+1 分档）。
 */
@Service
@RequiredArgsConstructor
public class PortfolioBackTestEngine {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int LIMIT_DOWN_FORCE_DAYS = 3;
    private static final int PENDING_BUY_EXPIRE_DAYS = 5;

    private final QuantProperties props;
    private final MarketDataService marketDataService;
    private final PositionAmountUtil positionAmountUtil;
    private final TradeCostModel tradeCostModel;
    private final OpenFilterService openFilterService;
    private final MaCrossStrategy maCrossStrategy;

    public PortfolioResultDTO run(BackTestQueryDTO query) {
        if (query == null || query.getStockCodeList() == null || query.getStockCodeList().isEmpty()) {
            return PortfolioResultDTO.empty(BigDecimal.ZERO);
        }
        BigDecimal initCapital = query.getInitCapital() == null
                ? new BigDecimal("100000") : query.getInitCapital();
        BigDecimal commissionRate = query.getFeeRate() != null ? query.getFeeRate() : props.getFeeRate();

        Map<String, List<BarDTO>> barMap = new HashMap<String, List<BarDTO>>();
        Map<String, IndicatorSignalUtil.IndicatorBundle> indMap = new HashMap<String, IndicatorSignalUtil.IndicatorBundle>();
        TreeSet<LocalDateTime> timeSet = new TreeSet<LocalDateTime>();
        for (String code : query.getStockCodeList()) {
            List<BarDTO> bars = marketDataService.getKline(code, BarPeriod.DAY, query.getBackStart(), query.getBackEnd());
            if (bars.size() < 65) {
                continue;
            }
            barMap.put(code, bars);
            indMap.put(code, IndicatorSignalUtil.precompute(bars));
            for (BarDTO b : bars) {
                timeSet.add(b.getBarBegin());
            }
        }
        if (barMap.isEmpty()) {
            return PortfolioResultDTO.empty(initCapital);
        }

        Map<String, StockBook> books = new HashMap<String, StockBook>();
        for (String code : barMap.keySet()) {
            books.put(code, new StockBook());
        }

        AccountRiskState accountRisk = new AccountRiskState(props);
        accountRisk.reset(initCapital);

        BigDecimal cash = initCapital;
        BigDecimal peak = initCapital;
        BigDecimal maxDd = BigDecimal.ZERO;
        List<BackTradeRecord> trades = new ArrayList<BackTradeRecord>();
        List<String> equityTimes = new ArrayList<String>();
        List<BigDecimal> equityCurve = new ArrayList<BigDecimal>();

        List<LocalDateTime> timeline = new ArrayList<LocalDateTime>(timeSet);
        LocalDate currentDay = null;
        int step = 0;

        for (LocalDateTime t : timeline) {
            LocalDate tradeDay = t.toLocalDate();
            if (currentDay == null || !currentDay.equals(tradeDay)) {
                currentDay = tradeDay;
                for (StockBook b : books.values()) {
                    b.stoppedOutToday = false;
                    b.pos.clearAddedToday();
                }
            }

            // Step1: 撮合
            for (String code : barMap.keySet()) {
                List<BarDTO> bars = barMap.get(code);
                int idx = findIndex(bars, t, books.get(code).hint);
                if (idx < 0) {
                    continue;
                }
                books.get(code).hint = idx;
                StockBook book = books.get(code);
                BarDTO bar = bars.get(idx);
                BigDecimal open = bar.getOpen();

                if (book.pendingBuyVol != null && book.pendingBuySignalDay != null
                        && tradeDay.isAfter(book.pendingBuySignalDay.plusDays(PENDING_BUY_EXPIRE_DAYS))) {
                    book.pendingBuyVol = null;
                    book.pendingBuyPyramid = false;
                    book.pendingBuySignalDay = null;
                }

                if (book.pendingSell && book.pos.hasPosition() && book.pendingSellSignalDay != null
                        && tradeDay.isAfter(book.pendingSellSignalDay)
                        && FillTimingHelper.canFillPendingOnBar(bars, idx)) {
                    boolean limitDown = openFilterService.isLimitDownAt(bars, idx);
                    if (limitDown && book.limitDownFailDays < LIMIT_DOWN_FORCE_DAYS) {
                        if (book.lastLimitDownFailDay == null || !book.lastLimitDownFailDay.equals(tradeDay)) {
                            book.limitDownFailDays++;
                            book.lastLimitDownFailDay = tradeDay;
                        }
                        if (book.limitDownFailDays < LIMIT_DOWN_FORCE_DAYS) {
                            // skip fill
                        } else {
                            BigDecimal prev = openFilterService.prevTradingDayClose(bars, idx);
                            BigDecimal force = LimitBoardHelper.limitDownPrice(prev, code);
                            if (force == null) {
                                force = open;
                            }
                            force = force.multiply(new BigDecimal("0.99")).setScale(2, RoundingMode.HALF_UP);
                            cash = doSell(code, book, bars, idx, cash, force, book.pos.getShares(), true,
                                    commissionRate, trades, tradeDay, accountRisk);
                        }
                    } else if (!limitDown) {
                        cash = doSell(code, book, bars, idx, cash, open, book.pos.getShares(), true,
                                commissionRate, trades, tradeDay, accountRisk);
                    }
                }

                if (book.pendingBuyVol != null && book.pendingBuySignalDay != null
                        && tradeDay.isAfter(book.pendingBuySignalDay)
                        && FillTimingHelper.canFillPendingOnBar(bars, idx)
                        && openFilterService.canExecuteOpenFill(code, bars, idx)) {
                    cash = doBuy(code, book, bars, idx, cash, open, commissionRate, trades, tradeDay, accountRisk);
                }
            }

            // Step2: 老仓止损
            if (props.isStopLossEnabled()) {
                for (String code : barMap.keySet()) {
                    List<BarDTO> bars = barMap.get(code);
                    int idx = findIndex(bars, t, books.get(code).hint);
                    if (idx < 0) {
                        continue;
                    }
                    StockBook book = books.get(code);
                    BarDTO bar = bars.get(idx);
                    if (!book.pos.hasPosition() || !book.pos.canSellStops(tradeDay)) {
                        if (book.pos.hasPosition()) {
                            book.pos.updateHighest(bar.getHigh());
                        }
                        continue;
                    }
                    book.pos.updateHighest(bar.getHigh());
                    int sellable = (book.pos.sellableShares(tradeDay) / 100) * 100;
                    if (sellable >= 100
                            && book.pos.getStopPrice().compareTo(BigDecimal.ZERO) > 0
                            && bar.getLow().compareTo(book.pos.getStopPrice()) <= 0) {
                        BigDecimal fill = book.pos.getStopPrice().max(bar.getLow());
                        boolean full = sellable >= book.pos.getShares();
                        cash = doSell(code, book, bars, idx, cash, fill, sellable, full,
                                commissionRate, trades, tradeDay, accountRisk);
                        if (full) {
                            book.stoppedOutToday = true;
                        }
                    }
                }
            }

            BigDecimal equity = calcEquity(cash, books, barMap, t);
            accountRisk.onEquity(tradeDay, equity);
            BigDecimal posScale = accountRisk.positionScale(equity);

            // Step3 halt → pending sell all
            if (accountRisk.isHalted()) {
                for (StockBook book : books.values()) {
                    if (book.pos.hasPosition() && !book.pendingSell) {
                        book.pendingSell = true;
                        book.pendingSellSignalDay = tradeDay;
                    }
                }
            }

            // Step4: 信号
            for (String code : barMap.keySet()) {
                List<BarDTO> bars = barMap.get(code);
                IndicatorSignalUtil.IndicatorBundle ind = indMap.get(code);
                int idx = findIndex(bars, t, books.get(code).hint);
                if (idx < 0) {
                    continue;
                }
                StockBook book = books.get(code);
                BarDTO bar = bars.get(idx);
                BigDecimal close = bar.getClose();
                boolean buySignal = maCrossStrategy.isBuySignalAt(ind, idx);
                boolean sellSignal = ind.isMaCrossDown(idx);

                if (!book.pos.hasPosition() && buySignal && !book.pendingSell && book.pendingBuyVol == null
                        && accountRisk.allowNewOpen(tradeDay, equity)
                        && posScale.compareTo(BigDecimal.ZERO) > 0
                        && openFilterService.canOpen(code, bars, idx)) {
                    BigDecimal atr = atrAt(ind, idx);
                    if (atr.compareTo(props.getAtrMinThreshold()) > 0) {
                        book.targetFullVol = positionAmountUtil.calcBuyVolume(cash, close, atr, posScale);
                        int first = props.isPyramidEnabled()
                                ? positionAmountUtil.pyramidSlice(book.targetFullVol, 0) : book.targetFullVol;
                        if (first >= 100) {
                            book.pendingBuyVol = first;
                            book.pendingBuyPyramid = false;
                            book.pendingBuySignalDay = tradeDay;
                        }
                    }
                } else if (book.pos.hasPosition() && props.isPyramidEnabled()
                        && book.pyramidStage >= 1 && book.pyramidStage < 3
                        && book.pendingBuyVol == null
                        && !book.pos.isAddedToday()
                        && close.compareTo(book.pos.getAvgCost().multiply(BigDecimal.ONE.add(props.getPyramidAddPct()))) >= 0
                        && ind.ma5[idx] > ind.ma20[idx]
                        && accountRisk.allowNewOpen(tradeDay, equity)
                        && posScale.compareTo(BigDecimal.ZERO) > 0) {
                    int slice = positionAmountUtil.pyramidSlice(book.targetFullVol, book.pyramidStage);
                    BigDecimal posMv = close.multiply(BigDecimal.valueOf(book.pos.getShares()));
                    BigDecimal addMoney = close.multiply(BigDecimal.valueOf(Math.max(slice, 0)));
                    if (slice >= 100 && positionAmountUtil.withinTotalPosition(equity, calcPosMv(books, barMap, t), addMoney)) {
                        book.pendingBuyVol = slice;
                        book.pendingBuyPyramid = true;
                        book.pendingBuySignalDay = tradeDay;
                    }
                }

                if (book.pos.hasPosition() && sellSignal && !book.stoppedOutToday && !book.pendingSell) {
                    book.pendingSell = true;
                    book.pendingSellSignalDay = tradeDay;
                }

                if (book.pos.hasPosition() && props.isTrailingStopEnabled()) {
                    book.pos.raiseTrailingStop(atrAt(ind, idx), props.getTrailingAtrMultiplier());
                }

                // 分股权益峰值/回撤（按该股持仓市值+已实现）
                BigDecimal stockMv = book.pos.hasPosition()
                        ? close.multiply(BigDecimal.valueOf(book.pos.getShares())) : BigDecimal.ZERO;
                BigDecimal stockEq = book.realized.add(stockMv);
                if (stockEq.compareTo(book.peakEq) > 0) {
                    book.peakEq = stockEq;
                }
                if (book.peakEq.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal dd = book.peakEq.subtract(stockEq).divide(book.peakEq, 6, RoundingMode.HALF_UP);
                    if (dd.compareTo(book.maxDd) > 0) {
                        book.maxDd = dd;
                    }
                }
            }

            equity = calcEquity(cash, books, barMap, t);
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }
            if (peak.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal dd = peak.subtract(equity).divide(peak, 6, RoundingMode.HALF_UP);
                if (dd.compareTo(maxDd) > 0) {
                    maxDd = dd;
                }
            }
            if (step % 30 == 0 || step == timeline.size() - 1) {
                equityTimes.add(t.format(FMT));
                equityCurve.add(equity.setScale(2, RoundingMode.HALF_UP));
            }
            // 日末固化账户昨收权益
            boolean dayEnd = step == timeline.size() - 1
                    || !timeline.get(step + 1).toLocalDate().equals(tradeDay);
            if (dayEnd) {
                accountRisk.onDayClose(equity);
            }
            step++;
        }

        BigDecimal finalAsset = calcEquity(cash, books, barMap, timeline.get(timeline.size() - 1));
        BigDecimal totalRate = finalAsset.subtract(initCapital).divide(initCapital, 6, RoundingMode.HALF_UP);

        int totalWin = 0;
        int totalRound = 0;
        List<SingleStockBackResult> stockResults = new ArrayList<SingleStockBackResult>();
        for (String code : barMap.keySet()) {
            StockBook book = books.get(code);
            totalWin += book.winRounds;
            totalRound += book.closedRounds;
            BigDecimal wr = book.closedRounds == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(book.winRounds)
                    .divide(BigDecimal.valueOf(book.closedRounds), 4, RoundingMode.HALF_UP);
            BigDecimal realized = book.realized.setScale(2, RoundingMode.HALF_UP);
            BigDecimal contrib = initCapital.compareTo(BigDecimal.ZERO) <= 0 ? BigDecimal.ZERO
                    : realized.divide(initCapital, 6, RoundingMode.HALF_UP);
            stockResults.add(SingleStockBackResult.builder()
                    .stockCode(code)
                    .totalTradeNum(book.tradeCount)
                    .winRate(wr)
                    .totalRate(contrib)
                    .maxDrawDown(book.maxDd)
                    .finalAsset(realized)
                    .build());
        }
        stockResults.sort(Comparator.comparing(SingleStockBackResult::getStockCode));
        BigDecimal winRate = totalRound == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(totalWin).divide(BigDecimal.valueOf(totalRound), 4, RoundingMode.HALF_UP);

        DecisionAnalysisLog analysis = DecisionAnalysisLog.fromTrades(trades);
        return PortfolioResultDTO.builder()
                .initCapital(initCapital)
                .finalAsset(finalAsset.setScale(2, RoundingMode.HALF_UP))
                .totalRate(totalRate)
                .maxDrawDown(maxDd)
                .totalTradeNum(trades.size())
                .winRate(winRate)
                .equityTimes(equityTimes)
                .equityCurve(equityCurve)
                .stockResults(stockResults)
                .trades(trades)
                .analysisEvents(analysis.events())
                .analysisSummary(analysis.summary() + "（组合：由成交流水生成；完整信号依据见单股分析）")
                .build();
    }

    private BigDecimal doBuy(String code, StockBook book, List<BarDTO> bars, int idx,
                             BigDecimal cash, BigDecimal base, BigDecimal commissionRate,
                             List<BackTradeRecord> trades, LocalDate tradeDay, AccountRiskState risk) {
        int vol = book.pendingBuyVol == null ? 0 : book.pendingBuyVol;
        boolean pyramid = book.pendingBuyPyramid;
        book.pendingBuyVol = null;
        book.pendingBuyPyramid = false;
        book.pendingBuySignalDay = null;
        vol = (vol / 100) * 100;
        if (vol < 100) {
            return cash;
        }
        BigDecimal deal = tradeCostModel.buyPrice(base, bars, idx, vol);
        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
        BigDecimal fee = tradeCostModel.buyFee(amount, commissionRate);
        BigDecimal equity = cash.add(calcPosMvOne(book, bars.get(idx).getClose()));
        // 使用组合权益粗略校验
        if (amount.add(fee).compareTo(cash) > 0) {
            return cash;
        }
        cash = cash.subtract(amount).subtract(fee);
        book.pos.addBuy(vol, deal, fee, tradeDay);
        book.pos.raiseStopByCost(atrAt(IndicatorSignalUtil.precompute(bars), idx),
                equity, props.getAtrStopMultiplier(), props.getHardStopCapitalPct());
        book.tradeCount++;
        trades.add(rec(code, "BUY", bars.get(idx).getBarBegin(), deal, vol, fee, amount));
        if (pyramid) {
            book.pyramidStage++;
        } else {
            book.pyramidStage = Math.max(book.pyramidStage, 1);
        }
        return cash;
    }

    private BigDecimal doSell(String code, StockBook book, List<BarDTO> bars, int idx,
                              BigDecimal cash, BigDecimal base, int vol, boolean clearAll,
                              BigDecimal commissionRate, List<BackTradeRecord> trades,
                              LocalDate tradeDay, AccountRiskState risk) {
        vol = (vol / 100) * 100;
        if (vol < 100 || !book.pos.hasPosition()) {
            return cash;
        }
        BigDecimal avg = book.pos.getAvgCost();
        BigDecimal deal = tradeCostModel.sellPrice(base, bars, idx, vol);
        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
        BigDecimal fee = tradeCostModel.sellFee(amount, commissionRate);
        BigDecimal pnl = deal.subtract(avg).multiply(BigDecimal.valueOf(vol)).subtract(fee);
        cash = cash.add(amount).subtract(fee);
        trades.add(rec(code, "SELL", bars.get(idx).getBarBegin(), deal, vol, fee, amount));
        book.tradeCount++;
        book.realized = book.realized.add(pnl);
        if (clearAll || vol >= book.pos.getShares()) {
            book.pos.clear();
            book.pyramidStage = 0;
            book.targetFullVol = 0;
            book.closedRounds++;
            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                book.winRounds++;
            }
            risk.onClosedRound(pnl.compareTo(BigDecimal.ZERO) > 0, tradeDay);
        } else {
            book.pos.removeShares(vol);
        }
        book.pendingSell = false;
        book.pendingSellSignalDay = null;
        book.limitDownFailDays = 0;
        book.lastLimitDownFailDay = null;
        return cash;
    }

    private int findIndex(List<BarDTO> bars, LocalDateTime t, Integer hint) {
        int idx = hint == null || hint < 0 ? 0 : hint;
        while (idx < bars.size() && bars.get(idx).getBarBegin().isBefore(t)) {
            idx++;
        }
        if (idx >= bars.size() || !bars.get(idx).getBarBegin().equals(t)) {
            return -1;
        }
        return idx;
    }

    private BackTradeRecord rec(String code, String side, LocalDateTime t, BigDecimal deal,
                                int vol, BigDecimal fee, BigDecimal amount) {
        return BackTradeRecord.builder()
                .stockCode(code).side(side).tradeTime(t)
                .price(deal).volume(vol).fee(fee).amount(amount).build();
    }

    private BigDecimal calcEquity(BigDecimal cash, Map<String, StockBook> books,
                                  Map<String, List<BarDTO>> barMap, LocalDateTime t) {
        return cash.add(calcPosMv(books, barMap, t));
    }

    private BigDecimal calcPosMv(Map<String, StockBook> books, Map<String, List<BarDTO>> barMap, LocalDateTime t) {
        BigDecimal mv = BigDecimal.ZERO;
        for (Map.Entry<String, StockBook> e : books.entrySet()) {
            if (!e.getValue().pos.hasPosition()) {
                continue;
            }
            BigDecimal price = findPriceAt(barMap.get(e.getKey()), t);
            mv = mv.add(price.multiply(BigDecimal.valueOf(e.getValue().pos.getShares())));
        }
        return mv;
    }

    private BigDecimal calcPosMvOne(StockBook book, BigDecimal price) {
        if (!book.pos.hasPosition() || price == null) {
            return BigDecimal.ZERO;
        }
        return price.multiply(BigDecimal.valueOf(book.pos.getShares()));
    }

    private BigDecimal findPriceAt(List<BarDTO> bars, LocalDateTime t) {
        BigDecimal last = bars.get(0).getClose();
        for (BarDTO b : bars) {
            if (b.getBarBegin().isAfter(t)) {
                break;
            }
            last = b.getClose();
        }
        return last;
    }

    private static BigDecimal atrAt(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        if (ind == null || i < 0 || Double.isNaN(ind.atr14[i])) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(ind.atr14[i]);
    }

    private static final class StockBook {
        final PositionState pos = new PositionState();
        Integer hint = 0;
        Integer pendingBuyVol;
        boolean pendingBuyPyramid;
        LocalDate pendingBuySignalDay;
        boolean pendingSell;
        LocalDate pendingSellSignalDay;
        int pyramidStage;
        int targetFullVol;
        int limitDownFailDays;
        LocalDate lastLimitDownFailDay;
        boolean stoppedOutToday;
        int tradeCount;
        int closedRounds;
        int winRounds;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal peakEq = BigDecimal.ZERO;
        BigDecimal maxDd = BigDecimal.ZERO;
    }
}
