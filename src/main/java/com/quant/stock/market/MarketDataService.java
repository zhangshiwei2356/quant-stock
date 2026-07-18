package com.quant.stock.market;

import cn.hutool.core.util.StrUtil;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 统一K线查询入口：对外按 BarPeriod 路由分表，屏蔽存储细节。
 * <p>
 * 查询优先级：classpath JSON → Redis → MySQL分表 → mock/sdk
 */
@Slf4j
@Service
public class MarketDataService {

    private static final String CACHE_KEY_PREFIX = "quant:kline:";

    private final QuantProperties quantProperties;
    private final JsonBarDataStore jsonBarDataStore;
    private final KlineSdkClient klineSdkClient;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    /** 启用 quant.db-enabled=true 后注入 */
    @Autowired(required = false)
    private BarStorageService barStorageService;

    public MarketDataService(QuantProperties quantProperties, JsonBarDataStore jsonBarDataStore,
                             KlineSdkClient klineSdkClient) {
        this.quantProperties = quantProperties;
        this.jsonBarDataStore = jsonBarDataStore;
        this.klineSdkClient = klineSdkClient;
    }

    /**
     * 统一查询：优先读对应周期表；缺失则从1分钟降级实时聚合
     */
    public List<BarDTO> getKline(String code, BarPeriod period, LocalDateTime start, LocalDateTime end) {
        if (StrUtil.isBlank(code)) {
            return new ArrayList<BarDTO>();
        }
        if (period == null) {
            period = BarPeriod.MIN_1;
        }

        // 0) classpath JSON 模拟数据
        if (jsonBarDataStore.available()) {
            List<BarDTO> fromJson = jsonBarDataStore.getBars(code, period, start, end);
            if (fromJson != null && !fromJson.isEmpty()) {
                return BarAggregateUtil.filterClosedBars(fromJson);
            }
            if (!period.isRaw()) {
                List<BarDTO> min1 = jsonBarDataStore.getBars(code, BarPeriod.MIN_1, start, end);
                if (min1 != null && !min1.isEmpty()) {
                    return BarAggregateUtil.filterClosedBars(
                            BarAggregateUtil.aggregate(min1, period.getAggregatePeriod()));
                }
            }
        }

        List<BarDTO> cached = getFromCache(code, period);
        if (cached != null && !cached.isEmpty()) {
            return filterByTime(BarAggregateUtil.filterClosedBars(cached), start, end);
        }

        // 1) 优先读分表
        if (barStorageService != null) {
            try {
                List<BarDTO> fromTable = barStorageService.loadBars(code, period, start, end);
                if (fromTable != null && !fromTable.isEmpty()) {
                    List<BarDTO> closed = BarAggregateUtil.filterClosedBars(fromTable);
                    putCache(code, period, closed);
                    return closed;
                }
                // 2) 聚合表无数据：从1min降级实时聚合
                if (!period.isRaw()) {
                    List<BarDTO> minute = barStorageService.loadBars(code, BarPeriod.MIN_1, start, end);
                    if (minute != null && !minute.isEmpty()) {
                        List<BarDTO> agg = BarAggregateUtil.aggregate(minute, period.getAggregatePeriod());
                        List<BarDTO> closed = BarAggregateUtil.filterClosedBars(agg);
                        putCache(code, period, closed);
                        return closed;
                    }
                }
            } catch (Exception e) {
                log.warn("分表查询失败，降级内存行情 code={} period={}: {}", code, period, e.getMessage());
            }
        }

        // 3) 无库 / 无数据：mock 或 sdk 拉1分钟，再按需聚合
        List<BarDTO> minuteBars = loadMinuteBarsInternal(code);
        minuteBars = filterByTime(minuteBars, start, end);
        if (period.isRaw()) {
            return BarAggregateUtil.filterClosedBars(minuteBars);
        }
        return BarAggregateUtil.filterClosedBars(
                BarAggregateUtil.aggregate(minuteBars, period.getAggregatePeriod()));
    }

    public List<BarDTO> loadMinuteBars(String code) {
        return getKline(code, BarPeriod.MIN_1, null, null);
    }

    public List<BarDTO> loadMinuteBars(String code, LocalDateTime start, LocalDateTime end) {
        return getKline(code, BarPeriod.MIN_1, start, end);
    }

    /**
     * 拉取原始1分钟并可选落库（仅写 stock_bar_1min）
     */
    public List<BarDTO> fetchAndPersist1Min(String code) {
        List<BarDTO> bars = loadMinuteBarsInternal(code);
        if (barStorageService != null && bars != null && !bars.isEmpty()) {
            try {
                barStorageService.save1MinBars(bars);
                log.info("1分钟K已落库 code={} size={}", code, bars.size());
            } catch (Exception e) {
                log.warn("1分钟K落库失败 code={}: {}", code, e.getMessage());
            }
        }
        return BarAggregateUtil.filterClosedBars(bars);
    }

    private List<BarDTO> loadMinuteBarsInternal(String code) {
        if (jsonBarDataStore.available()) {
            List<BarDTO> fromJson = jsonBarDataStore.getBars(code, BarPeriod.MIN_1);
            if (fromJson != null && !fromJson.isEmpty()) {
                return fromJson;
            }
        }
        List<BarDTO> cached = getFromCache(code, BarPeriod.MIN_1);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        List<BarDTO> bars;
        if ("sdk".equalsIgnoreCase(quantProperties.getMarketMode())) {
            bars = loadKlineFromSdk(code);
            if (bars == null || bars.isEmpty()) {
                log.warn("SDK行情为空，回退 mock 数据, code={}", code);
                bars = generateMockBars(code, quantProperties.getMockBarDays());
            }
        } else {
            bars = generateMockBars(code, quantProperties.getMockBarDays());
        }
        putCache(code, BarPeriod.MIN_1, bars);
        return bars;
    }

    /** 对接 {@link KlineSdkClient}；默认 Noop 返回空，由上层回退 mock */
    protected List<BarDTO> loadKlineFromSdk(String code) {
        List<BarDTO> bars = klineSdkClient.fetchMinuteBars(code);
        if (bars == null) {
            return new ArrayList<BarDTO>();
        }
        return bars;
    }

    private List<BarDTO> filterByTime(List<BarDTO> bars, LocalDateTime start, LocalDateTime end) {
        if (bars == null) {
            return new ArrayList<BarDTO>();
        }
        if (start == null && end == null) {
            return bars;
        }
        List<BarDTO> filtered = new ArrayList<BarDTO>();
        for (BarDTO bar : bars) {
            if (start != null && bar.getBarBegin().isBefore(start)) {
                continue;
            }
            if (end != null && bar.getBarBegin().isAfter(end)) {
                continue;
            }
            filtered.add(bar);
        }
        return filtered;
    }

    @SuppressWarnings("unchecked")
    private List<BarDTO> getFromCache(String code, BarPeriod period) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            Object val = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + period.name() + ":" + code);
            if (val instanceof List) {
                return (List<BarDTO>) val;
            }
        } catch (Exception e) {
            log.debug("Redis读取行情失败: {}", e.getMessage());
        }
        return null;
    }

    private void putCache(String code, BarPeriod period, List<BarDTO> bars) {
        if (redisTemplate == null || bars == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(CACHE_KEY_PREFIX + period.name() + ":" + code, bars, 5, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.debug("Redis写入行情失败: {}", e.getMessage());
        }
    }

    public List<BarDTO> generateMockBars(String code, int tradingDays) {
        List<BarDTO> bars = new ArrayList<BarDTO>();
        long seed = Math.abs(code.hashCode());
        Random random = new Random(seed);
        BigDecimal price = basePrice(code);
        LocalDate day = LocalDate.now().minusDays(tradingDays + 10);
        int generatedDays = 0;

        while (generatedDays < tradingDays) {
            DayOfWeek dow = day.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                bars.addAll(generateSession(code, day, LocalTime.of(9, 30), 120, price, random, generatedDays));
                price = bars.get(bars.size() - 1).getClose();
                bars.addAll(generateSession(code, day, LocalTime.of(13, 0), 120, price, random, generatedDays));
                price = bars.get(bars.size() - 1).getClose();
                generatedDays++;
            }
            day = day.plusDays(1);
        }
        return bars;
    }

    private List<BarDTO> generateSession(String code, LocalDate day, LocalTime start, int minutes,
                                         BigDecimal startPrice, Random random, int dayIndex) {
        List<BarDTO> list = new ArrayList<BarDTO>();
        BigDecimal price = startPrice;
        double trend = Math.sin(dayIndex / 3.0) * 0.002 + (random.nextDouble() - 0.5) * 0.0005;
        for (int i = 0; i < minutes; i++) {
            LocalDateTime begin = LocalDateTime.of(day, start).plusMinutes(i);
            double noise = (random.nextDouble() - 0.5) * 0.003;
            BigDecimal open = price;
            BigDecimal close = open.multiply(BigDecimal.valueOf(1 + trend + noise))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal high = open.max(close).multiply(BigDecimal.valueOf(1 + random.nextDouble() * 0.002))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal low = open.min(close).multiply(BigDecimal.valueOf(1 - random.nextDouble() * 0.002))
                    .setScale(2, RoundingMode.HALF_UP);
            if (low.compareTo(BigDecimal.ZERO) <= 0) {
                low = close.min(open).multiply(new BigDecimal("0.999"));
            }
            BigDecimal volume = BigDecimal.valueOf(1000 + random.nextInt(9000));
            list.add(BarDTO.builder()
                    .code(code)
                    .barBegin(begin)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .build());
            price = close;
        }
        return list;
    }

    private BigDecimal basePrice(String code) {
        if (code.startsWith("6")) {
            return new BigDecimal("25.00");
        }
        if (code.startsWith("3")) {
            return new BigDecimal("18.50");
        }
        return new BigDecimal("12.80");
    }
}
