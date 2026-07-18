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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 核心行情读取：仅物理表 market_daily + market_minute(5min)。
 * 其他周期由内存聚合生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class CoreMarketBarService {

    private final MarketDailyMapper marketDailyMapper;
    private final MarketMinuteMapper marketMinuteMapper;

    public boolean hasDaily(String symbol) {
        return marketDailyMapper.countBySymbol(symbol) > 0;
    }

    public boolean hasMinute(String symbol) {
        return marketMinuteMapper.countBySymbol(symbol) > 0;
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
