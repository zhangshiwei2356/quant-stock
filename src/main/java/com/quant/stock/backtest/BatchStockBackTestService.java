package com.quant.stock.backtest;

import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.backtest.dto.BatchScanResultDTO;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.strategy.IndicatorSignalUtil;
import com.quant.stock.strategy.StrategyRegistry;
import com.quant.stock.strategy.dto.TradeSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final StrategyRegistry strategyRegistry;
    private final Executor batchScanExecutor;

    public BatchStockBackTestService(QuantProperties quantProperties,
                                     MarketDataService marketDataService,
                                     BackTestEngine backTestEngine,
                                     StrategyRegistry strategyRegistry,
                                     @Qualifier("batchScanExecutor") Executor batchScanExecutor) {
        this.quantProperties = quantProperties;
        this.marketDataService = marketDataService;
        this.backTestEngine = backTestEngine;
        this.strategyRegistry = strategyRegistry;
        this.batchScanExecutor = batchScanExecutor;
    }

    /** 扫描进度回调（完成一只报一次；调用方可降频）。 */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int done, int total, String symbol);
    }

    /** 扫描配置股票池（默认 quant.stock-codes） */
    public List<BatchScanResultDTO> scanAll() {
        return scan(quantProperties.stockCodeList());
    }

    /** 并发扫描指定代码列表（完整回测，供批量扫描页）。 */
    public List<BatchScanResultDTO> scan(List<String> codes) {
        return scan(codes, null, false);
    }

    /** 并发扫描；可选进度回调。 */
    public List<BatchScanResultDTO> scan(List<String> codes, ProgressCallback progress) {
        return scan(codes, progress, false);
    }

    /**
     * 并发扫描。
     *
     * @param light true=入池轻量模式（不算完整回测，只指标/信号/动量）；false=含 BackTestEngine
     */
    public List<BatchScanResultDTO> scan(List<String> codes, ProgressCallback progress, boolean light) {
        if (codes == null || codes.isEmpty()) {
            return new ArrayList<BatchScanResultDTO>();
        }
        final int total = codes.size();
        final boolean lightMode = light;
        if (progress != null) {
            try {
                progress.onProgress(0, total, null);
            } catch (Exception ignored) {
                log.error("批量回测单股异常", ignored);
                // 进度不影响主流程
            }
        }
        final AtomicInteger done = new AtomicInteger();
        List<CompletableFuture<BatchScanResultDTO>> futures = new ArrayList<CompletableFuture<BatchScanResultDTO>>();
        for (final String code : codes) {
            futures.add(CompletableFuture.supplyAsync(new java.util.function.Supplier<BatchScanResultDTO>() {
                @Override
                public BatchScanResultDTO get() {
                    try {
                        return scanOne(code, lightMode);
                    } finally {
                        int d = done.incrementAndGet();
                        if (progress != null && (d == 1 || d == total || d % 10 == 0)) {
                            try {
                                progress.onProgress(d, total, code);
                            } catch (Exception ignored) {
                                log.error("批量回测单股异常", ignored);
                                // 进度不影响主流程
                            }
                        }
                    }
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
                .collect(Collectors.toList());
        log.info("批量扫描完成, 输入={} 有效={} light={}", codes.size(), results.size(), lightMode);
        return results;
    }

    /** 单股日线扫描分析（供推荐详情等复用；含完整回测）。 */
    public BatchScanResultDTO analyzeOne(String code) {
        return scanOne(code, false);
    }

    private BatchScanResultDTO scanOne(String code, boolean light) {
        try {
            List<BarDTO> bars = marketDataService.getKline(code,
                    com.quant.stock.market.BarPeriod.DAY, null, null);
            if (bars.size() < 20) {
                log.debug("跳过K线不足20根: {}", code);
                return null;
            }
            Map<String, BigDecimal> ind = IndicatorSignalUtil.calcLatestIndicators(bars);
            TradeSignal signal = strategyRegistry.active().calcSignal(code, bars);
            boolean canBuy = signal.getSignalType() == TradeSignal.Signal.BUY;
            BigDecimal close = ind.getOrDefault("close", bars.get(bars.size() - 1).getClose());
            BigDecimal mom5 = momReturn(bars, 5);
            BigDecimal mom20 = momReturn(bars, 20);
            BigDecimal volMa20 = ind.get("volMa20");
            BigDecimal avgAmt = null;
            if (volMa20 != null && close != null) {
                avgAmt = volMa20.multiply(close);
            }
            Boolean ma60SlopeUp = null;
            BigDecimal ma60 = ind.get("ma60");
            BigDecimal ma60Prev5 = ind.get("ma60Prev5");
            if (ma60 != null && ma60Prev5 != null) {
                ma60SlopeUp = ma60.compareTo(ma60Prev5) > 0;
            }

            BigDecimal totalRate = null;
            BigDecimal maxDrawDown = null;
            BigDecimal winRate = null;
            Integer totalTradeNum = null;
            if (!light) {
                BigDecimal init = new BigDecimal("100000");
                BackTestResult bt = backTestEngine.run(code, bars, init);
                totalRate = bt.getTotalRate();
                maxDrawDown = bt.getMaxDrawDown();
                winRate = bt.getWinRate();
                totalTradeNum = bt.getTotalTradeNum();
            }

            return BatchScanResultDTO.builder()
                    .stockCode(code)
                    .lastClose(close)
                    .totalRate(totalRate)
                    .maxDrawDown(maxDrawDown)
                    .winRate(winRate)
                    .totalTradeNum(totalTradeNum)
                    .canBuyNow(canBuy)
                    .signalDesc(signal.getSignalDesc())
                    .ma5(ind.get("ma5"))
                    .ma10(ind.get("ma10"))
                    .ma20(ind.get("ma20"))
                    .ma60(ma60)
                    .rsi14(ind.get("rsi14"))
                    .atr14(ind.get("atr14"))
                    .adx14(ind.get("adx14"))
                    .mom5(mom5)
                    .mom20(mom20)
                    .avgAmount20(avgAmt)
                    .ma60SlopeUp(ma60SlopeUp)
                    .build();
        } catch (Exception e) {
            log.error("扫描失败 {}: {}", code, e.getMessage(), e);
            return null;
        }
    }

    /** (close_now - close_n) / close_n；K 线不足返回 null */
    private static BigDecimal momReturn(List<BarDTO> bars, int n) {
        if (bars == null || bars.size() <= n) {
            return null;
        }
        BigDecimal now = bars.get(bars.size() - 1).getClose();
        BigDecimal past = bars.get(bars.size() - 1 - n).getClose();
        if (now == null || past == null || past.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return now.subtract(past).divide(past, 6, RoundingMode.HALF_UP);
    }
}
