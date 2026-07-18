package com.quant.stock.backtest;

import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.backtest.dto.BatchScanResultDTO;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.strategy.IndicatorSignalUtil;
import com.quant.stock.strategy.MaCrossStrategy;
import com.quant.stock.strategy.dto.TradeSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 批量股票池扫描（线程池并发）
 */
@Slf4j
@Service
public class BatchStockBackTestService {

    private final QuantProperties quantProperties;
    private final MarketDataService marketDataService;
    private final BackTestEngine backTestEngine;
    private final MaCrossStrategy maCrossStrategy;
    private final Executor batchScanExecutor;

    public BatchStockBackTestService(QuantProperties quantProperties,
                                     MarketDataService marketDataService,
                                     BackTestEngine backTestEngine,
                                     MaCrossStrategy maCrossStrategy,
                                     @Qualifier("batchScanExecutor") Executor batchScanExecutor) {
        this.quantProperties = quantProperties;
        this.marketDataService = marketDataService;
        this.backTestEngine = backTestEngine;
        this.maCrossStrategy = maCrossStrategy;
        this.batchScanExecutor = batchScanExecutor;
    }

    public List<BatchScanResultDTO> scanAll() {
        return scan(quantProperties.stockCodeList());
    }

    public List<BatchScanResultDTO> scan(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return new ArrayList<BatchScanResultDTO>();
        }
        List<CompletableFuture<BatchScanResultDTO>> futures = new ArrayList<CompletableFuture<BatchScanResultDTO>>();
        for (final String code : codes) {
            futures.add(CompletableFuture.supplyAsync(new java.util.function.Supplier<BatchScanResultDTO>() {
                @Override
                public BatchScanResultDTO get() {
                    return scanOne(code);
                }
            }, batchScanExecutor));
        }
        List<BatchScanResultDTO> results = futures.stream()
                .map(new java.util.function.Function<CompletableFuture<BatchScanResultDTO>, BatchScanResultDTO>() {
                    @Override
                    public BatchScanResultDTO apply(CompletableFuture<BatchScanResultDTO> f) {
                        return f.join();
                    }
                })
                .filter(new java.util.function.Predicate<BatchScanResultDTO>() {
                    @Override
                    public boolean test(BatchScanResultDTO r) {
                        return r != null;
                    }
                })
                .sorted(Comparator.comparing(BatchScanResultDTO::getTotalRate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
        log.info("批量扫描完成, 输入={} 有效={}", codes.size(), results.size());
        return results;
    }

    private BatchScanResultDTO scanOne(String code) {
        try {
            List<BarDTO> bars = marketDataService.getKline(code,
                    com.quant.stock.market.BarPeriod.DAY, null, null);
            if (bars.size() < 20) {
                log.debug("跳过K线不足20根: {}", code);
                return null;
            }
            BigDecimal init = new BigDecimal("100000");
            BackTestResult bt = backTestEngine.run(code, bars, init);
            Map<String, BigDecimal> ind = IndicatorSignalUtil.calcLatestIndicators(bars);
            TradeSignal signal = maCrossStrategy.calcSignal(code, bars);
            boolean canBuy = signal.getSignalType() == TradeSignal.Signal.BUY;

            return BatchScanResultDTO.builder()
                    .stockCode(code)
                    .lastClose(ind.getOrDefault("close", bars.get(bars.size() - 1).getClose()))
                    .totalRate(bt.getTotalRate())
                    .maxDrawDown(bt.getMaxDrawDown())
                    .winRate(bt.getWinRate())
                    .totalTradeNum(bt.getTotalTradeNum())
                    .canBuyNow(canBuy)
                    .signalDesc(signal.getSignalDesc())
                    .ma5(ind.get("ma5"))
                    .ma20(ind.get("ma20"))
                    .rsi14(ind.get("rsi14"))
                    .atr14(ind.get("atr14"))
                    .build();
        } catch (Exception e) {
            log.warn("扫描失败 {}: {}", code, e.getMessage());
            return null;
        }
    }
}
