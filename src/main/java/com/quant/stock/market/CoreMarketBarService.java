package com.quant.stock.market;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.mapper.MarketDailyMapper;
import com.quant.stock.mapper.Market1MinMapper;
import com.quant.stock.mapper.MarketMinuteMapper;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.market.dto.Market1MinDO;
import com.quant.stock.market.dto.MarketDailyDO;
import com.quant.stock.market.dto.MarketMinuteDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 核心行情读写：market_1min 为原始分钟层，market_daily 与 market_minute(5min) 保留作缓存/回退。
 * 其他周期由内存聚合生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class CoreMarketBarService {

    private static final int BATCH_SIZE = 500;

    private final MarketDailyMapper marketDailyMapper;
    private final Market1MinMapper market1MinMapper;
    private final MarketMinuteMapper marketMinuteMapper;
    private final QuantProperties quantProperties;

    /** 是否已有 {@code market_daily} 数据 */
    public boolean hasDaily(String symbol) {
        return marketDailyMapper.countBySymbol(symbol) > 0;
    }

    /** 是否已有 {@code market_minute} 数据 */
    public boolean hasMinute(String symbol) {
        return marketMinuteMapper.countBySymbol(symbol) > 0;
    }

    /** 是否已有 {@code market_1min} 数据。 */
    public boolean hasOneMin(String symbol) {
        return market1MinMapper.countBySymbol(symbol) > 0;
    }

    /** 是否已有足够的 {@code market_1min} 数据可作为原始分钟层。 */
    public boolean hasEnoughOneMin(String symbol) {
        return market1MinMapper.countBySymbol(symbol) >= quantProperties.getMin1minBars();
    }

    /**
     * 写入/更新 market_1min（物理 1 分钟 K）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int saveMinutes1(List<BarDTO> bars) {
        if (bars == null || bars.isEmpty()) {
            return 0;
        }
        List<Market1MinDO> list = new ArrayList<Market1MinDO>(bars.size());
        for (BarDTO bar : bars) {
            Market1MinDO row = Market1MinDO.fromBarDTO(bar);
            if (row != null && row.getSymbol() != null && row.getTradeTime() != null) {
                list.add(row);
            }
        }
        if (list.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < list.size(); i += BATCH_SIZE) {
            int to = Math.min(i + BATCH_SIZE, list.size());
            total += market1MinMapper.batchUpsert(list.subList(i, to));
        }
        return total;
    }

    /**
     * 写入/更新 market_minute（物理 5 分钟 K）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int saveMinutes(List<BarDTO> bars) {
        if (bars == null || bars.isEmpty()) {
            return 0;
        }
        List<MarketMinuteDO> list = new ArrayList<MarketMinuteDO>(bars.size());
        for (BarDTO bar : bars) {
            MarketMinuteDO row = MarketMinuteDO.fromBarDTO(bar);
            if (row != null && row.getSymbol() != null && row.getTradeTime() != null) {
                list.add(row);
            }
        }
        if (list.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < list.size(); i += BATCH_SIZE) {
            int to = Math.min(i + BATCH_SIZE, list.size());
            total += marketMinuteMapper.batchUpsert(list.subList(i, to));
        }
        return total;
    }

    /**
     * 写入/更新 market_daily。
     */
    @Transactional(rollbackFor = Exception.class)
    public int saveDailies(List<BarDTO> bars) {
        if (bars == null || bars.isEmpty()) {
            return 0;
        }
        List<MarketDailyDO> list = new ArrayList<MarketDailyDO>(bars.size());
        for (BarDTO bar : bars) {
            MarketDailyDO row = MarketDailyDO.fromBarDTO(bar);
            if (row != null && row.getSymbol() != null && row.getTradeDate() != null) {
                list.add(row);
            }
        }
        if (list.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < list.size(); i += BATCH_SIZE) {
            int to = Math.min(i + BATCH_SIZE, list.size());
            total += marketDailyMapper.batchUpsert(list.subList(i, to));
        }
        return total;
    }

    /**
     * 收盘清算：优先用当日 {@code market_1min}，否则用 {@code market_minute} 聚合成日 K 写入 {@code market_daily}。
     * 15/30/60/周/月不落物理表，由 {@link #load} 按需内存聚合。
     */
    @Transactional(rollbackFor = Exception.class)
    public int upsertDailyFromMinutes(String code, LocalDate tradeDay) {
        if (code == null || tradeDay == null) {
            return 0;
        }
        LocalDateTime start = tradeDay.atTime(9, 30);
        LocalDateTime end = tradeDay.atTime(15, 0);
        List<BarDTO> minutes = loadOneMin(code, start, end);
        String source = "market_1min";
        if (minutes.isEmpty()) {
            minutes = loadMinute(code, start, end);
            source = "market_minute";
        }
        if (minutes.isEmpty()) {
            log.debug("无分钟数据可聚日线 code={} day={}", code, tradeDay);
            return 0;
        }
        List<BarDTO> days = BarAggregateUtil.aggregate(minutes, BarAggregateUtil.Period.DAY);
        int n = saveDailies(days);
        log.info("日线已由分钟聚合落库 market_daily code={} day={} source={} upsert≈{}",
                code, tradeDay, source, n);
        return n;
    }

    /**
     * 从核心表读取 K 线；周/月及 15/30/60 分钟由内存聚合。
     */
    public List<BarDTO> load(String code, BarPeriod period, LocalDateTime start, LocalDateTime end) {
        if (period == null) {
            period = BarPeriod.DAY;
        }
        String source = normalizeSource();
        boolean enoughOneMin = hasEnoughOneMin(code);
        if ("prefer_1min".equals(source) && !enoughOneMin) {
            return new ArrayList<BarDTO>();
        }
        if (!"legacy".equals(source) && enoughOneMin) {
            List<BarDTO> ones = loadOneMin(code, start, end);
            switch (period) {
                case MIN_1:
                    return ones;
                case MIN_5:
                    return BarAggregateUtil.aggregate(ones, BarAggregateUtil.Period.M5);
                case MIN_15:
                    return BarAggregateUtil.aggregate(ones, BarAggregateUtil.Period.M15);
                case MIN_30:
                    return BarAggregateUtil.aggregate(ones, BarAggregateUtil.Period.M30);
                case MIN_60:
                    return BarAggregateUtil.aggregate(ones, BarAggregateUtil.Period.M60);
                case DAY:
                    return BarAggregateUtil.aggregate(ones, BarAggregateUtil.Period.DAY);
                case WEEK:
                    return BarAggregateUtil.aggregate(ones, BarAggregateUtil.Period.WEEK);
                case MONTH:
                    return BarAggregateUtil.aggregate(ones, BarAggregateUtil.Period.MONTH);
                default:
                    return new ArrayList<BarDTO>();
            }
        }
        switch (period) {
            case DAY:
                return loadDaily(code, start, end);
            case WEEK:
                return BarAggregateUtil.aggregate(loadDaily(code, start, end),
                        BarAggregateUtil.Period.WEEK);
            case MONTH:
                return BarAggregateUtil.aggregate(loadDaily(code, start, end),
                        BarAggregateUtil.Period.MONTH);
            case MIN_5:
            case MIN_1:
                // 物理真相源为 5 分钟；MIN_1 请求降级为 5 分钟序列
                return loadMinute(code, start, end);
            case MIN_15:
                return BarAggregateUtil.aggregate(loadMinute(code, start, end),
                        BarAggregateUtil.Period.M15);
            case MIN_30:
                return BarAggregateUtil.aggregate(loadMinute(code, start, end),
                        BarAggregateUtil.Period.M30);
            case MIN_60:
                return BarAggregateUtil.aggregate(loadMinute(code, start, end),
                        BarAggregateUtil.Period.M60);
            default:
                return new ArrayList<BarDTO>();
        }
    }

    private String normalizeSource() {
        String source = quantProperties.getKlineSource();
        return source == null ? "auto" : source.trim().toLowerCase();
    }

    private List<BarDTO> loadDaily(String code, LocalDateTime start, LocalDateTime end) {
        LocalDate s = start == null ? null : start.toLocalDate();
        LocalDate e = end == null ? null : end.toLocalDate();
        List<MarketDailyDO> rows = marketDailyMapper.selectRange(code, s, e);
        List<BarDTO> out = new ArrayList<BarDTO>(rows.size());
        for (MarketDailyDO row : rows) {
            out.add(row.toBarDTO());
        }
        return out;
    }

    private List<BarDTO> loadMinute(String code, LocalDateTime start, LocalDateTime end) {
        List<MarketMinuteDO> rows = marketMinuteMapper.selectRange(code, start, end);
        List<BarDTO> out = new ArrayList<BarDTO>(rows.size());
        for (MarketMinuteDO row : rows) {
            out.add(row.toBarDTO());
        }
        return out;
    }

    private List<BarDTO> loadOneMin(String code, LocalDateTime start, LocalDateTime end) {
        List<Market1MinDO> rows = market1MinMapper.selectRange(code, start, end);
        List<BarDTO> out = new ArrayList<BarDTO>(rows.size());
        for (Market1MinDO row : rows) {
            out.add(row.toBarDTO());
        }
        return out;
    }
}
