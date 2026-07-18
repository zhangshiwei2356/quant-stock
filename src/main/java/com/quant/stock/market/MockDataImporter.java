package com.quant.stock.market;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.quant.stock.mapper.FactorDailyMapper;
import com.quant.stock.mapper.MarketDailyMapper;
import com.quant.stock.mapper.MarketMinuteMapper;
import com.quant.stock.mapper.StockBasicMapper;
import com.quant.stock.market.dto.FactorDailyDO;
import com.quant.stock.market.dto.MarketDailyDO;
import com.quant.stock.market.dto.MarketMinuteDO;
import com.quant.stock.market.dto.StockBasicDO;
import com.quant.stock.risk.LimitBoardHelper;
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
 * 启动时若 market_daily 为空，则从 classpath:data/kline 导入 DAY + MIN_5 模拟数据。
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
    private final MarketDailyMapper marketDailyMapper;
    private final MarketMinuteMapper marketMinuteMapper;
    private final FactorDailyMapper factorDailyMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            importIfNeeded();
        } catch (Exception e) {
            log.error("模拟数据导入失败: {}", e.getMessage(), e);
        }
    }

    public void importIfNeeded() throws Exception {
        String probe = "000001";
        if (marketDailyMapper.countBySymbol(probe) > 0 && marketMinuteMapper.countBySymbol(probe) > 0) {
            log.info("MySQL 已有行情数据，跳过 JSON 导入");
            return;
        }
        log.info("开始从 classpath JSON 导入模拟行情到 MySQL ...");
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
        for (int i = 0; i < stocks.size(); i++) {
            JSONObject s = stocks.getJSONObject(i);
            String code = s.getString("code");
            String name = s.getString("name");
            upsertBasic(code, name);
            importDaily(resolver, code);
            importMinute(resolver, code);
            computeFactors(code);
            log.info("导入完成 symbol={}", code);
        }
        log.info("全部模拟数据已写入 MySQL（market_daily / market_minute / factor_daily / stock_basic）");
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

    private void importDaily(PathMatchingResourcePatternResolver resolver, String code) throws Exception {
        JSONArray bars = loadBarsArray(resolver, code, "DAY");
        if (bars == null || bars.isEmpty()) {
            return;
        }
        List<MarketDailyDO> batch = new ArrayList<MarketDailyDO>(BATCH);
        BigDecimal prevClose = null;
        for (int i = 0; i < bars.size(); i++) {
            JSONArray row = bars.getJSONArray(i);
            LocalDateTime t = LocalDateTime.parse(row.getString(0), FMT);
            BigDecimal open = bd(row.get(1));
            BigDecimal high = bd(row.get(2));
            BigDecimal low = bd(row.get(3));
            BigDecimal close = bd(row.get(4));
            long volume = row.getLongValue(5);
            BigDecimal amount = close.multiply(BigDecimal.valueOf(volume)).setScale(4, RoundingMode.HALF_UP);
            BigDecimal limitUp = null;
            BigDecimal limitDown = null;
            if (prevClose != null) {
                limitUp = LimitBoardHelper.limitUpPrice(prevClose, code);
                limitDown = LimitBoardHelper.limitDownPrice(prevClose, code);
            }
            batch.add(MarketDailyDO.builder()
                    .symbol(code)
                    .tradeDate(t.toLocalDate())
                    .open(open).high(high).low(low).close(close)
                    .volume(volume)
                    .amount(amount)
                    .limitUp(limitUp)
                    .limitDown(limitDown)
                    .build());
            prevClose = close;
            if (batch.size() >= BATCH) {
                marketDailyMapper.batchUpsert(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            marketDailyMapper.batchUpsert(batch);
        }
        log.info("market_daily 写入 {} bars={}", code, bars.size());
    }

    private void importMinute(PathMatchingResourcePatternResolver resolver, String code) throws Exception {
        JSONArray bars = loadBarsArray(resolver, code, "MIN_5");
        if (bars == null || bars.isEmpty()) {
            return;
        }
        List<MarketMinuteDO> batch = new ArrayList<MarketMinuteDO>(BATCH);
        for (int i = 0; i < bars.size(); i++) {
            JSONArray row = bars.getJSONArray(i);
            LocalDateTime t = LocalDateTime.parse(row.getString(0), FMT);
            BigDecimal open = bd(row.get(1));
            BigDecimal high = bd(row.get(2));
            BigDecimal low = bd(row.get(3));
            BigDecimal close = bd(row.get(4));
            long volume = row.getLongValue(5);
            BigDecimal amount = close.multiply(BigDecimal.valueOf(volume)).setScale(4, RoundingMode.HALF_UP);
            batch.add(MarketMinuteDO.builder()
                    .symbol(code)
                    .tradeTime(t)
                    .open(open).high(high).low(low).close(close)
                    .volume(volume)
                    .amount(amount)
                    .build());
            if (batch.size() >= BATCH) {
                marketMinuteMapper.batchUpsert(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            marketMinuteMapper.batchUpsert(batch);
        }
        log.info("market_minute 写入 {} bars={}", code, bars.size());
    }

    private void computeFactors(String code) {
        List<MarketDailyDO> days = marketDailyMapper.selectRange(code, null, null);
        if (days.isEmpty()) {
            return;
        }
        factorDailyMapper.deleteBySymbol(code);
        List<FactorDailyDO> factors = new ArrayList<FactorDailyDO>(days.size());
        for (int i = 0; i < days.size(); i++) {
            MarketDailyDO d = days.get(i);
            FactorDailyDO f = FactorDailyDO.builder()
                    .symbol(code)
                    .tradeDate(d.getTradeDate())
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
                f.setIsVolumeBreak(BigDecimal.valueOf(d.getVolume()).compareTo(thr) >= 0 ? 1 : 0);
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
            log.warn("缺少文件 {}/{}.json", code, period);
            return null;
        }
        JSONObject obj = JSON.parseObject(readAll(res.getInputStream()));
        return obj.getJSONArray("bars");
    }

    private static BigDecimal smaClose(List<MarketDailyDO> days, int idx, int n) {
        if (idx + 1 < n) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int j = idx - n + 1; j <= idx; j++) {
            sum = sum.add(days.get(j).getClose());
        }
        return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal smaVol(List<MarketDailyDO> days, int idx, int n) {
        if (idx + 1 < n) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int j = idx - n + 1; j <= idx; j++) {
            sum = sum.add(BigDecimal.valueOf(days.get(j).getVolume()));
        }
        return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal rsi(List<MarketDailyDO> days, int idx, int n) {
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

    private static BigDecimal atr(List<MarketDailyDO> days, int idx, int n) {
        if (idx < n) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int j = idx - n + 1; j <= idx; j++) {
            MarketDailyDO cur = days.get(j);
            MarketDailyDO prev = days.get(j - 1);
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
