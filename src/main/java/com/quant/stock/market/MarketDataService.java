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
 * 统一K线查询入口：对外按 BarPeriod 路由，屏蔽存储细节。
 * <p>
 * 查询优先级：MySQL(market_daily/minute) → Redis → 旧分表 → classpath JSON → mock/sdk
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

    /** 启用 quant.db-enabled=true 后注入：核心日线/5分钟表 */
    @Autowired(required = false)
    private CoreMarketBarService coreMarketBarService;

    /** 兼容旧 stock_bar_* 分表 */
    @Autowired(required = false)
    private BarStorageService barStorageService;

    public MarketDataService(QuantProperties quantProperties, JsonBarDataStore jsonBarDataStore,
                             KlineSdkClient klineSdkClient) {
        this.quantProperties = quantProperties;
        this.jsonBarDataStore = jsonBarDataStore;
        this.klineSdkClient = klineSdkClient;
    }

    /**
     * 统一查询
     */
    public List<BarDTO> getKline(String code, BarPeriod period, LocalDateTime start, LocalDateTime end) {
        if (StrUtil.isBlank(code)) {
            return new ArrayList<BarDTO>();
        }
        if (period == null) {
            period = BarPeriod.MIN_1;
        }

        // 0) MySQL 核心表（market_daily / market_minute）
        if (coreMarketBarService != null) {
            try {
                List<BarDTO> fromDb = coreMarketBarService.load(code, period, start, end);
                if (fromDb != null && !fromDb.isEmpty()) {
                    List<BarDTO> closed = BarAggregateUtil.filterClosedBars(fromDb);
                    putCache(code, period, closed);
                    return closed;
                }
            } catch (Exception e) {
                log.warn("核心行情表查询失败 code={} period={}: {}", code, period, e.getMessage());
            }
        }

        List<BarDTO> cached = getFromCache(code, period);
        if (cached != null && !cached.isEmpty()) {
            return filterByTime(BarAggregateUtil.filterClosedBars(cached), start, end);
        }

        // 1) 兼容旧分表
        if (barStorageService != null) {
            try {
                List<BarDTO> fromTable = barStorageService.loadBars(code, period, start, end);
                if (fromTable != null && !fromTable.isEmpty()) {
                    List<BarDTO> closed = BarAggregateUtil.filterClosedBars(fromTable);
                    putCache(code, period, closed);
                    return closed;
                }
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
                log.warn("分表查询失败，降级 code={} period={}: {}", code, period, e.getMessage());
            }
        }

        // 2) classpath JSON（仅作兜底；正式环境以 MySQL 为准）
        if (jsonBarDataStore.available() && !"db".equalsIgnoreCase(quantProperties.getMarketMode())) {
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

        // 3) mock / sdk
        List<BarDTO> minuteBars = loadMinuteBarsInternal(code);
        minuteBars = filterByTime(minuteBars, start, end);
        if (period.isRaw() || period == BarPeriod.MIN_5) {
            return BarAggregateUtil.filterClosedBars(minuteBars);
        }
        return BarAggregateUtil.filterClosedBars(
                BarAggregateUtil.aggregate(minuteBars, period.getAggregatePeriod()));
    }

    /**
     * 加载分钟序列：物理真相源为 {@link BarPeriod#MIN_5}（market_minute）。
     * 对外仍称「分钟线」，非真正 1 分钟 Tick。
     */
    public List<BarDTO> loadMinuteBars(String code) {
        return getKline(code, BarPeriod.MIN_5, null, null);
    }

    public List<BarDTO> loadMinuteBars(String code, LocalDateTime start, LocalDateTime end) {
        return getKline(code, BarPeriod.MIN_5, start, end);
    }

    /**
     * 拉取分钟行情并落库到 {@code market_minute}（5 分钟物理表）。
     * <p>
     * 不再写入 legacy {@code stock_bar_1min}，避免把 5 分钟 bar 误存成 1 分钟。
     */
    public List<BarDTO> fetchAndPersistMinute(String code) {
        List<BarDTO> bars = loadMinuteBarsInternal(code);
        if (bars == null || bars.isEmpty()) {
            return new ArrayList<BarDTO>();
        }
        if (coreMarketBarService != null) {
            try {
                int n = coreMarketBarService.saveMinutes(bars);
                log.info("分钟K已落库 market_minute code={} size={} upsert≈{}", code, bars.size(), n);
            } catch (Exception e) {
                log.warn("分钟K落库失败 code={}: {}", code, e.getMessage());
            }
        } else {
            log.debug("CoreMarketBarService 未启用，跳过 market_minute 落库 code={}", code);
        }
        return BarAggregateUtil.filterClosedBars(bars);
    }

    /**
     * @deprecated 命名易误解；请用 {@link #fetchAndPersistMinute}
     */
    @Deprecated
    public List<BarDTO> fetchAndPersist1Min(String code) {
        return fetchAndPersistMinute(code);
    }

    /** 内部加载：优先核心 5 分钟表，再 JSON/SDK/mock */
    private List<BarDTO> loadMinuteBarsInternal(String code) {
        if (coreMarketBarService != null) {
            try {
                List<BarDTO> fromDb = coreMarketBarService.load(code, BarPeriod.MIN_5, null, null);
                if (fromDb != null && !fromDb.isEmpty()) {
                    return fromDb;
                }
            } catch (Exception e) {
                log.debug("读取 market_minute 失败 code={}: {}", code, e.getMessage());
            }
        }
        if (jsonBarDataStore.available() && !"db".equalsIgnoreCase(quantProperties.getMarketMode())) {
            // 种子 JSON 的 MIN_5；若无则尝试 MIN_1 仅作兜底展示
            List<BarDTO> fromJson5 = jsonBarDataStore.getBars(code, BarPeriod.MIN_5);
            if (fromJson5 != null && !fromJson5.isEmpty()) {
                return fromJson5;
            }
            List<BarDTO> fromJson1 = jsonBarDataStore.getBars(code, BarPeriod.MIN_1);
            if (fromJson1 != null && !fromJson1.isEmpty()) {
                // 兜底：1 分钟种子聚成 5 分钟，避免误写入 market_minute
                return BarAggregateUtil.aggregate(fromJson1, BarAggregateUtil.Period.M5);
            }
        }
        List<BarDTO> cached = getFromCache(code, BarPeriod.MIN_5);
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
        putCache(code, BarPeriod.MIN_5, bars);
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
                // 每根 5 分钟：上午/下午各 24 根（120 分钟）
                bars.addAll(generateSession(code, day, LocalTime.of(9, 30), 24, price, random, generatedDays));
                price = bars.get(bars.size() - 1).getClose();
                bars.addAll(generateSession(code, day, LocalTime.of(13, 0), 24, price, random, generatedDays));
                price = bars.get(bars.size() - 1).getClose();
                generatedDays++;
            }
            day = day.plusDays(1);
        }
        return bars;
    }

    /** 生成 step=5 分钟的会话 K（barCount 根） */
    private List<BarDTO> generateSession(String code, LocalDate day, LocalTime start, int barCount,
                                         BigDecimal startPrice, Random random, int dayIndex) {
        List<BarDTO> list = new ArrayList<BarDTO>();
        BigDecimal price = startPrice;
        double trend = Math.sin(dayIndex / 3.0) * 0.002 + (random.nextDouble() - 0.5) * 0.0005;
        for (int i = 0; i < barCount; i++) {
            LocalDateTime begin = LocalDateTime.of(day, start).plusMinutes(i * 5);
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
            BigDecimal volume = BigDecimal.valueOf(5000 + random.nextInt(45000));
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
