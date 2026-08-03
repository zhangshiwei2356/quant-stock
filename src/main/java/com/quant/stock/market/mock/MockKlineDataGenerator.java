package com.quant.stock.market.mock;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 离线生成模拟股票「截至 end 的近一年」K 线 JSON（默认 end=今天）。
 * <pre>
 * mvn -q -DskipTests compile exec:java -Dexec.mainClass=com.quant.stock.market.mock.MockKlineDataGenerator
 * mvn -q -DskipTests compile exec:java -Dexec.mainClass=com.quant.stock.market.mock.MockKlineDataGenerator "-Dexec.args=only=601318,000858"
 * mvn -q -DskipTests compile exec:java -Dexec.mainClass=com.quant.stock.market.mock.MockKlineDataGenerator "-Dexec.args=end=2026-08-04"
 * </pre>
 * 只写 {@code MIN_1}/{@code MIN_5}（空库灌种优先 MIN_1，否则拆 MIN_5）；更大周期查询时内存聚合。
 */
public class MockKlineDataGenerator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 与 quant.stock-codes / meta 演示十只一致 */
    private static final String[][] STOCKS = {
            {"600036", "招商银行", "35.00"},
            {"000001", "平安银行", "11.50"},
            {"300059", "东方财富", "18.80"},
            {"601318", "中国平安", "45.00"},
            {"000858", "五粮液", "128.00"},
            {"600519", "贵州茅台", "1680.00"},
            {"000568", "泸州老窖", "145.00"},
            {"002415", "海康威视", "32.00"},
            {"600276", "恒瑞医药", "42.00"},
            {"601166", "兴业银行", "18.50"}
    };

    private static LocalDate START;
    private static LocalDate END;

    /** CLI 入口：生成模拟 K 线 JSON 至 resources/data/kline（支持 only=、out=、end= 参数）。 */
    public static void main(String[] args) throws Exception {
        END = parseEnd(args);
        START = END.minusYears(1);
        Path outDir = resolveOutDir(args);
        Files.createDirectories(outDir);
        Set<String> only = parseOnly(args);
        System.out.println("输出目录: " + outDir.toAbsolutePath());
        System.out.println("区间(近一年): " + START + " ~ " + END);
        if (!only.isEmpty()) {
            System.out.println("仅生成: " + only);
        }

        List<Map<String, String>> stockMeta = loadOrInitStockMeta(outDir);

        for (String[] s : STOCKS) {
            String code = s[0];
            if (!only.isEmpty() && !only.contains(code)) {
                continue;
            }
            String name = s[1];
            BigDecimal base = new BigDecimal(s[2]);
            upsertStockMeta(stockMeta, code, name, base.toPlainString());

            System.out.println("生成 " + code + " " + name + " ...");
            List<BarDTO> min1 = generateYear1Min(code, base);
            System.out.println("  MIN_1 bars=" + min1.size());

            Path stockDir = outDir.resolve(code);
            Files.createDirectories(stockDir);
            writePeriod(stockDir, code, BarPeriod.MIN_1, min1);

            List<BarDTO> min5 = BarAggregateUtil.aggregate(min1, BarAggregateUtil.Period.M5);
            writePeriod(stockDir, code, BarPeriod.MIN_5, min5);
            System.out.println("  MIN_5 bars=" + min5.size());
        }

        // meta 保留目录内全部股票条目（含本次未重算的），但区间与描述按本次生成刷新
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("description", "演示股近一年模拟K线（生成时相对 end 回推一年；空库灌 MIN_1，否则拆 MIN_5）");
        meta.put("start", START.toString());
        meta.put("end", END.toString());
        meta.put("generatedAt", LocalDateTime.now().format(FMT));
        meta.put("stocks", stockMeta);
        meta.put("periods", new String[]{"MIN_1", "MIN_5"});
        meta.put("note", "字段采用紧凑数组 [t,o,h,l,c,v]；更大周期由应用内存聚合");
        Files.write(outDir.resolve("meta.json"),
                JSON.toJSONBytes(meta, JSONWriter.Feature.PrettyFormat));

        System.out.println("全部完成，stocks=" + stockMeta.size() + " range=" + START + " ~ " + END);
    }

    private static LocalDate parseEnd(String[] args) {
        if (args != null) {
            for (String a : args) {
                if (a != null && a.startsWith("end=")) {
                    return LocalDate.parse(a.substring(4).trim());
                }
            }
        }
        return LocalDate.now();
    }

    private static Set<String> parseOnly(String[] args) {
        Set<String> only = new HashSet<String>();
        if (args == null) {
            return only;
        }
        for (String a : args) {
            if (a == null) {
                continue;
            }
            if (a.startsWith("only=")) {
                only.addAll(Arrays.asList(a.substring(5).split(",")));
            }
        }
        only.remove("");
        return only;
    }

    private static List<Map<String, String>> loadOrInitStockMeta(Path outDir) throws IOException {
        Path metaPath = outDir.resolve("meta.json");
        List<Map<String, String>> list = new ArrayList<Map<String, String>>();
        if (Files.isRegularFile(metaPath)) {
            JSONObject old = JSON.parseObject(new String(Files.readAllBytes(metaPath), StandardCharsets.UTF_8));
            JSONArray arr = old.getJSONArray("stocks");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject s = arr.getJSONObject(i);
                    Map<String, String> m = new LinkedHashMap<String, String>();
                    m.put("code", s.getString("code"));
                    m.put("name", s.getString("name"));
                    m.put("basePrice", s.getString("basePrice"));
                    list.add(m);
                }
            }
        }
        for (String[] s : STOCKS) {
            upsertStockMeta(list, s[0], s[1], s[2]);
        }
        return list;
    }

    private static void upsertStockMeta(List<Map<String, String>> list, String code, String name, String base) {
        for (Map<String, String> m : list) {
            if (code.equals(m.get("code"))) {
                m.put("name", name);
                m.put("basePrice", base);
                return;
            }
        }
        Map<String, String> sm = new LinkedHashMap<String, String>();
        sm.put("code", code);
        sm.put("name", name);
        sm.put("basePrice", base);
        list.add(sm);
    }

    private static Path resolveOutDir(String[] args) {
        if (args != null) {
            for (String a : args) {
                if (a != null && a.startsWith("out=")) {
                    return Paths.get(a.substring(4));
                }
            }
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
        Files.write(stockDir.resolve(period.name() + ".json"), JSON.toJSONBytes(file));
    }

    /**
     * 生成指定代码在 {@link #START}～{@link #END} 区间内的 1 分钟 K（供离线 JSON 工具使用）。
     */
    public static List<BarDTO> generateYear1Min(String code, BigDecimal basePrice) {
        if (START == null || END == null) {
            END = LocalDate.now();
            START = END.minusYears(1);
        }
        List<BarDTO> bars = new ArrayList<BarDTO>(60000);
        Random random = new Random(code.hashCode() * 31L + 20260718L);
        BigDecimal price = basePrice;
        LocalDate day = START;
        int dayIndex = 0;

        while (!day.isAfter(END)) {
            DayOfWeek dow = day.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                double trend = Math.sin(dayIndex / 18.0) * 0.004
                        + Math.sin(dayIndex / 55.0) * 0.002
                        + (random.nextDouble() - 0.5) * 0.0008;
                if ("000858".equals(code)) {
                    trend += 0.0012;
                } else if ("601318".equals(code)) {
                    trend -= 0.0003;
                }
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
