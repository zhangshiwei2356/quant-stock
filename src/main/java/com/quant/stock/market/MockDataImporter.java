package com.quant.stock.market;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.quant.stock.mapper.FactorDailyMapper;
import com.quant.stock.mapper.StockBasicMapper;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.market.dto.FactorDailyDO;
import com.quant.stock.market.dto.StockBasicDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时若某演示股尚无 {@code market_1min}，则从 classpath:data/kline 导入 1 分钟种子；
 * 优先 {@code MIN_1.json}，否则将 {@code MIN_5.json} 拆成 5 根同价量分摊的 1 分钟 bar。
 * 日线/更大周期由查询时聚合，不再写入 {@code market_daily}/{@code market_minute}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class MockDataImporter {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String BASE = "classpath:data/kline/";
    private static final int BATCH = 400;

    private final StockBasicMapper stockBasicMapper;
    private final FactorDailyMapper factorDailyMapper;
    private final CoreMarketBarService coreMarketBarService;

    /** 应用就绪后触发：空库则导入 classpath 模拟 1 分钟行情种子。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            importIfNeeded();
        } catch (Exception e) {
            log.error("模拟数据导入失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 若库内尚无 1 分钟行情则自 classpath JSON 增量导入，并计算日频因子。
     */
    public void importIfNeeded() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource metaRes = resolver.getResource(BASE + "meta.json");
        if (!metaRes.exists()) {
            log.warn("未找到 meta.json，无法导入");
            return;
        }
        JSONObject meta = JSON.parseObject(readAll(metaRes.getInputStream()));
        JSONArray stocks = meta.getJSONArray("stocks");
        if (stocks == null || stocks.isEmpty()) {
            return;
        }
        int imported = 0;
        for (int i = 0; i < stocks.size(); i++) {
            JSONObject s = stocks.getJSONObject(i);
            String code = s.getString("code");
            String name = s.getString("name");
            upsertBasic(code, name);
            if (coreMarketBarService.hasOneMin(code)) {
                continue;
            }
            log.info("增量导入模拟 1 分钟行情 symbol={} ...", code);
            int n = importOneMin(resolver, code);
            if (n <= 0) {
                log.warn("跳过 symbol={}：无 MIN_1.json / MIN_5.json 可导入", code);
                continue;
            }
            computeFactors(code);
            imported++;
            log.info("导入完成 symbol={} bars={}", code, n);
        }
        if (imported == 0) {
            log.info("MySQL market_1min 已覆盖 meta 中全部股票，跳过 JSON 导入");
        } else {
            log.info("本次增量导入 {} 只股票到 market_1min", imported);
        }
    }

    private void upsertBasic(String code, String name) {
        int market = 1;
        if (code.startsWith("3")) {
            market = 2;
        } else if (code.startsWith("688")) {
            market = 3;
        } else if (code.startsWith("8") || code.startsWith("4")) {
            market = 4;
        }
        stockBasicMapper.upsert(StockBasicDO.builder()
                .symbol(code)
                .name(name == null ? code : name)
                .market(market)
                .industry("模拟行业")
                .listDate(LocalDate.of(2010, 1, 1))
                .isSt(0)
                .status(1)
                .build());
    }

    /** @return 写入的 1 分钟根数 */
    private int importOneMin(PathMatchingResourcePatternResolver resolver, String code) throws Exception {
        JSONArray bars = loadBarsArray(resolver, code, "MIN_1");
        List<BarDTO> dtos;
        if (bars != null && !bars.isEmpty()) {
            dtos = parseMinuteBars(code, bars, false);
        } else {
            bars = loadBarsArray(resolver, code, "MIN_5");
            if (bars == null || bars.isEmpty()) {
                return 0;
            }
            dtos = parseMinuteBars(code, bars, true);
        }
        for (int i = 0; i < dtos.size(); i += BATCH) {
            int to = Math.min(i + BATCH, dtos.size());
            coreMarketBarService.saveMinutes1(dtos.subList(i, to));
        }
        log.info("market_1min 写入 {} bars={}", code, dtos.size());
        return dtos.size();
    }

    /**
     * @param expandFiveMin true 时把每根 5 分钟拆成 5 根 1 分钟（OHLC 相同，量额均分）
     */
    private List<BarDTO> parseMinuteBars(String code, JSONArray bars, boolean expandFiveMin) {
        List<BarDTO> dtos = new ArrayList<BarDTO>(expandFiveMin ? bars.size() * 5 : bars.size());
        for (int i = 0; i < bars.size(); i++) {
            JSONArray row = bars.getJSONArray(i);
            LocalDateTime t = LocalDateTime.parse(row.getString(0), FMT);
            BigDecimal open = bd(row.get(1));
            BigDecimal high = bd(row.get(2));
            BigDecimal low = bd(row.get(3));
            BigDecimal close = bd(row.get(4));
            long volume = row.getLongValue(5);
            if (!expandFiveMin) {
                dtos.add(bar(code, t, open, high, low, close, volume));
                continue;
            }
            long volEach = volume / 5;
            long volRem = volume - volEach * 5;
            for (int j = 0; j < 5; j++) {
                long v = volEach + (j == 4 ? volRem : 0);
                dtos.add(bar(code, t.plusMinutes(j), open, high, low, close, v));
            }
        }
        return dtos;
    }

    private static BarDTO bar(String code, LocalDateTime t, BigDecimal open, BigDecimal high,
                              BigDecimal low, BigDecimal close, long volume) {
        return BarDTO.builder()
                .code(code)
                .barBegin(t)
                .periodMinutes(1)
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .volume(BigDecimal.valueOf(volume))
                .build();
    }

    private void computeFactors(String code) {
        List<BarDTO> days = coreMarketBarService.load(code, BarPeriod.DAY, null, null);
        if (days.isEmpty()) {
            return;
        }
        factorDailyMapper.deleteBySymbol(code);
        List<FactorDailyDO> factors = new ArrayList<FactorDailyDO>(days.size());
        for (int i = 0; i < days.size(); i++) {
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
                if (d.getVolume().compareTo(thr) >= 0) {
                    f.setIsVolumeBreak(1);
                } else {
                    f.setIsVolumeBreak(0);
                }
            }
            factors.add(f);
            if (factors.size() >= BATCH) {
                factorDailyMapper.batchUpsert(factors);
                factors.clear();
            }
        }
        if (!factors.isEmpty()) {
            factorDailyMapper.batchUpsert(factors);
        }
        log.info("factor_daily 写入 {} rows={}", code, days.size());
    }

    private JSONArray loadBarsArray(PathMatchingResourcePatternResolver resolver, String code, String period)
            throws Exception {
        Resource res = resolver.getResource(BASE + code + "/" + period + ".json");
        if (!res.exists()) {
            return null;
        }
        JSONObject obj = JSON.parseObject(readAll(res.getInputStream()));
        return obj.getJSONArray("bars");
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
            BigDecimal tr = tr1.max(tr2).max(tr3);
            sum = sum.add(tr);
        }
        return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal bd(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(v.toString());
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
