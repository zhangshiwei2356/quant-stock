package com.quant.stock.market.mock;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.quant.stock.market.BarAggregateUtil;
import com.quant.stock.market.BarPeriod;
import com.quant.stock.market.dto.BarDTO;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 离线生成三只股票近一年分层K线 JSON。
 * 运行：在项目根目录执行
 * mvn -q exec:java -Dexec.mainClass=com.quant.stock.market.mock.MockKlineDataGenerator
 */
public class MockKlineDataGenerator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final LocalDate END = LocalDate.of(2026, 7, 17);
    private static final LocalDate START = END.minusYears(1);

    private static final String[][] STOCKS = {
            {"600036", "招商银行", "35.00"},
            {"000001", "平安银行", "11.50"},
            {"300059", "东方财富", "18.80"}
    };

    public static void main(String[] args) throws Exception {
        Path outDir = resolveOutDir(args);
        Files.createDirectories(outDir);
        System.out.println("输出目录: " + outDir.toAbsolutePath());

        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("description", "三只股票近一年模拟K线（原始1分钟 + 预聚合多周期）");
        meta.put("start", START.toString());
        meta.put("end", END.toString());
        meta.put("generatedAt", LocalDateTime.now().format(FMT));
        List<Map<String, String>> stockMeta = new ArrayList<Map<String, String>>();

        for (String[] s : STOCKS) {
            String code = s[0];
            String name = s[1];
            BigDecimal base = new BigDecimal(s[2]);
            Map<String, String> sm = new LinkedHashMap<String, String>();
            sm.put("code", code);
            sm.put("name", name);
            sm.put("basePrice", base.toPlainString());
            stockMeta.add(sm);

            System.out.println("生成 " + code + " " + name + " ...");
            List<BarDTO> min1 = generateYear1Min(code, base);
            System.out.println("  MIN_1 bars=" + min1.size());

            Path stockDir = outDir.resolve(code);
            Files.createDirectories(stockDir);
            writePeriod(stockDir, code, BarPeriod.MIN_1, min1);

            for (BarPeriod period : BarPeriod.aggregatePeriods()) {
                List<BarDTO> agg = BarAggregateUtil.aggregate(min1, period.getAggregatePeriod());
                writePeriod(stockDir, code, period, agg);
                System.out.println("  " + period.name() + " bars=" + agg.size());
            }
        }

        meta.put("stocks", stockMeta);
        meta.put("periods", new String[]{
                "MIN_1", "MIN_5", "MIN_15", "MIN_30", "MIN_60", "DAY", "WEEK", "MONTH"
        });
        meta.put("note", "字段采用紧凑数组 [t,o,h,l,c,v]，启动时由 JsonBarDataStore 加载");
        Files.write(outDir.resolve("meta.json"),
                JSON.toJSONBytes(meta, JSONWriter.Feature.PrettyFormat));

        System.out.println("全部完成");
    }

    private static Path resolveOutDir(String[] args) {
        if (args != null && args.length > 0) {
            return Paths.get(args[0]);
        }
        Path p = Paths.get("src/main/resources/data/kline");
        if (Files.isDirectory(Paths.get("src/main/resources"))) {
            return p;
        }
        return Paths.get("quant-stock/src/main/resources/data/kline");
    }

    private static void writePeriod(Path stockDir, String code, BarPeriod period, List<BarDTO> bars)
            throws IOException {
        Map<String, Object> file = new LinkedHashMap<String, Object>();
        file.put("stockCode", code);
        file.put("period", period.name());
        file.put("table", period.getTableName());
        file.put("fields", new String[]{"t", "o", "h", "l", "c", "v"});
        file.put("count", bars.size());
        List<Object[]> rows = new ArrayList<Object[]>(bars.size());
        for (BarDTO b : bars) {
            rows.add(new Object[]{
                    b.getBarBegin().format(FMT),
                    b.getOpen(),
                    b.getHigh(),
                    b.getLow(),
                    b.getClose(),
                    b.getVolume() == null ? 0L : b.getVolume().longValue()
            });
        }
        file.put("bars", rows);
        Path target = stockDir.resolve(period.name() + ".json");
        // 紧凑写入减小体积
        Files.write(target, JSON.toJSONBytes(file));
    }

    /** 近一年交易日 1 分钟K（含趋势波段，便于金叉死叉演示） */
    public static List<BarDTO> generateYear1Min(String code, BigDecimal basePrice) {
        List<BarDTO> bars = new ArrayList<BarDTO>(60000);
        Random random = new Random(code.hashCode() * 31L + 20260718L);
        BigDecimal price = basePrice;
        LocalDate day = START;
        int dayIndex = 0;

        while (!day.isAfter(END)) {
            DayOfWeek dow = day.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                // 中期趋势 + 季节波动，保证 MA 交叉
                double trend = Math.sin(dayIndex / 18.0) * 0.004
                        + Math.sin(dayIndex / 55.0) * 0.002
                        + (random.nextDouble() - 0.5) * 0.0008;
                bars.addAll(session(code, day, LocalTime.of(9, 30), 120, price, random, trend));
                price = bars.get(bars.size() - 1).getClose();
                bars.addAll(session(code, day, LocalTime.of(13, 0), 120, price, random, trend));
                price = bars.get(bars.size() - 1).getClose();
                dayIndex++;
            }
            day = day.plusDays(1);
        }
        return bars;
    }

    private static List<BarDTO> session(String code, LocalDate day, LocalTime start, int minutes,
                                        BigDecimal startPrice, Random random, double dayTrend) {
        List<BarDTO> list = new ArrayList<BarDTO>(minutes);
        BigDecimal price = startPrice;
        for (int i = 0; i < minutes; i++) {
            LocalDateTime begin = LocalDateTime.of(day, start).plusMinutes(i);
            double noise = (random.nextDouble() - 0.5) * 0.0025;
            BigDecimal open = price;
            BigDecimal close = open.multiply(BigDecimal.valueOf(1 + dayTrend / 240.0 + noise))
                    .setScale(2, RoundingMode.HALF_UP);
            if (close.compareTo(new BigDecimal("0.50")) < 0) {
                close = new BigDecimal("0.50");
            }
            BigDecimal high = open.max(close)
                    .multiply(BigDecimal.valueOf(1 + random.nextDouble() * 0.0015))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal low = open.min(close)
                    .multiply(BigDecimal.valueOf(1 - random.nextDouble() * 0.0015))
                    .setScale(2, RoundingMode.HALF_UP);
            if (low.compareTo(BigDecimal.ZERO) <= 0) {
                low = close.min(open).multiply(new BigDecimal("0.999")).setScale(2, RoundingMode.HALF_UP);
            }
            long volume = 800L + random.nextInt(12000);
            list.add(BarDTO.builder()
                    .code(code)
                    .barBegin(begin)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(BigDecimal.valueOf(volume))
                    .build());
            price = close;
        }
        return list;
    }
}
