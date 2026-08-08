package com.quant.stock.market;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.quant.stock.mapper.StockBasicMapper;
import com.quant.stock.market.dto.BarDTO;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时若某演示股尚无 {@code market_1min}，则从 classpath:data/kline 导入 1 分钟种子（data_source=MOCK）；
 * 优先 {@code MIN_1.json}，否则将 {@code MIN_5.json} 拆成 5 根同价量分摊的 1 分钟 bar；
 * 并重算 {@code factor_daily}（日线优先 {@code market_daily}，否则由分钟聚日）。
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
    private final CoreMarketBarService coreMarketBarService;
    private final FactorDailyComputeService factorDailyComputeService;

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
            log.info("未找到 meta.json，无法导入");
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
                log.info("跳过 symbol={}：无 MIN_1.json / MIN_5.json 可导入", code);
                continue;
            }
            factorDailyComputeService.rebuildOne(code);
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
            coreMarketBarService.saveMinutes1(dtos.subList(i, to), MarketDataSources.MOCK);
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

    private JSONArray loadBarsArray(PathMatchingResourcePatternResolver resolver, String code, String period)
            throws Exception {
        Resource res = resolver.getResource(BASE + code + "/" + period + ".json");
        if (!res.exists()) {
            return null;
        }
        JSONObject obj = JSON.parseObject(readAll(res.getInputStream()));
        return obj.getJSONArray("bars");
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
