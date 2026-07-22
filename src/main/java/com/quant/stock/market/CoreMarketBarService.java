package com.quant.stock.market;

import com.quant.stock.mapper.MarketDailyMapper;
import com.quant.stock.mapper.MarketMinuteMapper;
import com.quant.stock.market.dto.BarDTO;
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
 * 核心行情读写：仅物理表 market_daily + market_minute(5min)。
 * 其他周期由内存聚合生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class CoreMarketBarService {

    private static final int BATCH_SIZE = 500;

    private final MarketDailyMapper marketDailyMapper;
    private final MarketMinuteMapper marketMinuteMapper;

    public boolean hasDaily(String symbol) {
        return marketDailyMapper.countBySymbol(symbol) > 0;
    }

    public boolean hasMinute(String symbol) {
        return marketMinuteMapper.countBySymbol(symbol) > 0;
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
     * 收盘清算：用当日 {@code market_minute} 聚合成日 K 写入 {@code market_daily}。
     * 15/30/60/周/月不落物理表，由 {@link #load} 按需内存聚合。
     */
    @Transactional(rollbackFor = Exception.class)
    public int upsertDailyFromMinutes(String code, LocalDate tradeDay) {
        if (code == null || tradeDay == null) {
            return 0;
        }
        LocalDateTime start = tradeDay.atTime(9, 30);
        LocalDateTime end = tradeDay.atTime(15, 0);
        List<BarDTO> minutes = loadMinute(code, start, end);
        if (minutes.isEmpty()) {
            log.debug("无分钟数据可聚日线 code={} day={}", code, tradeDay);
            return 0;
        }
        List<BarDTO> days = BarAggregateUtil.aggregate(minutes, BarAggregateUtil.Period.DAY);
        int n = saveDailies(days);
        log.info("日线已由分钟聚合落库 market_daily code={} day={} upsert≈{}", code, tradeDay, n);
        return n;
    }

    public List<BarDTO> load(String code, BarPeriod period, LocalDateTime start, LocalDateTime end) {
        if (period == null) {
            period = BarPeriod.DAY;
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
}
