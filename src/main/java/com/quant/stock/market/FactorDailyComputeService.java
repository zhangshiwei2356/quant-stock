package com.quant.stock.market;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.mapper.FactorDailyMapper;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.market.dto.FactorDailyDO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 由日线（优先 {@code market_daily}）重算 {@code factor_daily}，供入池粗筛使用。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class FactorDailyComputeService {

    private static final int BATCH = 400;

    private final FactorDailyMapper factorDailyMapper;
    private final CoreMarketBarService coreMarketBarService;
    private final JdbcTemplate jdbcTemplate;
    private final QuantProperties quantProperties;
    private final Executor batchScanExecutor;

    public FactorDailyComputeService(FactorDailyMapper factorDailyMapper,
                                     CoreMarketBarService coreMarketBarService,
                                     JdbcTemplate jdbcTemplate,
                                     QuantProperties quantProperties,
                                     @Qualifier("batchScanExecutor") Executor batchScanExecutor) {
        this.factorDailyMapper = factorDailyMapper;
        this.coreMarketBarService = coreMarketBarService;
        this.jdbcTemplate = jdbcTemplate;
        this.quantProperties = quantProperties;
        this.batchScanExecutor = batchScanExecutor;
    }

    /** 有日线数据的全部标的（{@code market_daily} 优先；空则回退有 1 分钟的演示股）。 */
    public List<String> listSymbolsWithDailyBars() {
        List<String> fromDaily = jdbcTemplate.queryForList(
                "SELECT DISTINCT symbol FROM market_daily ORDER BY symbol", String.class);
        if (fromDaily != null && !fromDaily.isEmpty()) {
            return fromDaily;
        }
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT symbol FROM market_1min ORDER BY symbol", String.class);
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int done, int total, String symbol);
    }

    /**
     * 批量重算；{@code codes} 为空则取有日线的全市场。
     *
     * @return 统计：input / ok / skip / fail
     */
    public Map<String, Object> rebuild(List<String> codes) {
        return rebuild(codes, null);
    }

    /**
     * 批量重算；可选进度回调（完成一只报一次，便于运维进度条）。
     */
    public Map<String, Object> rebuild(List<String> codes, ProgressCallback progress) {
        List<String> targets = codes;
        if (targets == null || targets.isEmpty()) {
            targets = listSymbolsWithDailyBars();
        }
        final int total = targets.size();
        if (progress != null) {
            try {
                progress.onProgress(0, total, null);
            } catch (Exception ignored) {
                // ignore
            }
        }
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger skip = new AtomicInteger();
        final AtomicInteger fail = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new ArrayList<CompletableFuture<Void>>(targets.size());
        for (final String code : targets) {
            futures.add(CompletableFuture.runAsync(new Runnable() {
                @Override
                public void run() {
                    try {
                        int n = rebuildOne(code);
                        if (n <= 0) {
                            skip.incrementAndGet();
                        } else {
                            ok.incrementAndGet();
                        }
                    } catch (Exception e) {
                        fail.incrementAndGet();
                        log.warn("factor_daily 重算失败 {}: {}", code, e.getMessage());
                    } finally {
                        int d = done.incrementAndGet();
                        // 降频：每 10 只或首尾上报，减轻进度槽竞争
                        if (progress != null && (d == 1 || d == total || d % 10 == 0)) {
                            try {
                                progress.onProgress(d, total, code);
                            } catch (Exception ignored) {
                                // 进度不影响主流程
                            }
                        }
                    }
                }
            }, batchScanExecutor));
        }
        for (CompletableFuture<Void> f : futures) {
            f.join();
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("input", targets.size());
        out.put("ok", ok.get());
        out.put("skip", skip.get());
        out.put("fail", fail.get());
        out.put("poolSize", quantProperties.getBatchPoolSize());
        log.info("factor_daily 批算完成 input={} ok={} skip={} fail={}",
                targets.size(), ok.get(), skip.get(), fail.get());
        return out;
    }

    /**
     * 单股全量重算：删旧行后按日线序列写入。
     *
     * @return 写入行数；无日线返回 0
     */
    public int rebuildOne(String code) {
        if (code == null || code.trim().isEmpty()) {
            return 0;
        }
        String symbol = code.trim();
        List<BarDTO> days = coreMarketBarService.load(symbol, BarPeriod.DAY, null, null);
        if (days == null || days.isEmpty()) {
            return 0;
        }
        factorDailyMapper.deleteBySymbol(symbol);
        List<FactorDailyDO> factors = new ArrayList<FactorDailyDO>(Math.min(days.size(), BATCH));
        int written = 0;
        for (int i = 0; i < days.size(); i++) {
            factors.add(buildFactor(symbol, days, i));
            if (factors.size() >= BATCH) {
                written += factorDailyMapper.batchUpsert(factors);
                factors.clear();
            }
        }
        if (!factors.isEmpty()) {
            written += factorDailyMapper.batchUpsert(factors);
        }
        log.debug("factor_daily 写入 {} rows≈{}", symbol, days.size());
        return days.size();
    }

    private static FactorDailyDO buildFactor(String code, List<BarDTO> days, int i) {
        BarDTO d = days.get(i);
        FactorDailyDO f = FactorDailyDO.builder()
                .symbol(code)
                .tradeDate(d.getBarBegin().toLocalDate())
                .ma5(smaClose(days, i, 5))
                .ma20(smaClose(days, i, 20))
                .ma60(smaClose(days, i, 60))
                .rsi14(rsi(days, i, 14))
                .atr14(atr(days, i, 14))
                .adx(null)
                .volumeMa20(smaVol(days, i, 20))
                .build();
        if (f.getMa60() != null && i >= 60) {
            BigDecimal prevMa60 = smaClose(days, i - 1, 60);
            f.setMa60Up(prevMa60 != null && f.getMa60().compareTo(prevMa60) > 0 ? 1 : 0);
        }
        if (f.getVolumeMa20() != null && d.getVolume() != null) {
            BigDecimal thr = f.getVolumeMa20().multiply(new BigDecimal("1.2"));
            f.setIsVolumeBreak(d.getVolume().compareTo(thr) >= 0 ? 1 : 0);
        }
        return f;
    }

    private static BigDecimal smaClose(List<BarDTO> days, int idx, int n) {
        if (idx + 1 < n) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int j = idx - n + 1; j <= idx; j++) {
            sum = sum.add(days.get(j).getClose());
        }
        return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal smaVol(List<BarDTO> days, int idx, int n) {
        if (idx + 1 < n) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int j = idx - n + 1; j <= idx; j++) {
            BigDecimal v = days.get(j).getVolume();
            sum = sum.add(v == null ? BigDecimal.ZERO : v);
        }
        return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal rsi(List<BarDTO> days, int idx, int n) {
        if (idx < n) {
            return null;
        }
        BigDecimal gain = BigDecimal.ZERO;
        BigDecimal loss = BigDecimal.ZERO;
        for (int j = idx - n + 1; j <= idx; j++) {
            BigDecimal diff = days.get(j).getClose().subtract(days.get(j - 1).getClose());
            if (diff.compareTo(BigDecimal.ZERO) >= 0) {
                gain = gain.add(diff);
            } else {
                loss = loss.add(diff.abs());
            }
        }
        if (loss.compareTo(BigDecimal.ZERO) == 0) {
            return new BigDecimal("100");
        }
        BigDecimal avgGain = gain.divide(BigDecimal.valueOf(n), 8, RoundingMode.HALF_UP);
        BigDecimal avgLoss = loss.divide(BigDecimal.valueOf(n), 8, RoundingMode.HALF_UP);
        BigDecimal rs = avgGain.divide(avgLoss, 8, RoundingMode.HALF_UP);
        return new BigDecimal("100").subtract(
                new BigDecimal("100").divide(BigDecimal.ONE.add(rs), 4, RoundingMode.HALF_UP));
    }

    private static BigDecimal atr(List<BarDTO> days, int idx, int n) {
        if (idx < n) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int j = idx - n + 1; j <= idx; j++) {
            BarDTO cur = days.get(j);
            BarDTO prev = days.get(j - 1);
            BigDecimal tr1 = cur.getHigh().subtract(cur.getLow());
            BigDecimal tr2 = cur.getHigh().subtract(prev.getClose()).abs();
            BigDecimal tr3 = cur.getLow().subtract(prev.getClose()).abs();
            sum = sum.add(tr1.max(tr2).max(tr3));
        }
        return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }
}
