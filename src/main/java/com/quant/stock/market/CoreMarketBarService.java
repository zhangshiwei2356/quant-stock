package com.quant.stock.market;

import com.quant.stock.mapper.Market1MinMapper;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.market.dto.Market1MinDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 核心行情读写：仅以 {@code market_1min} 为物理真相源；更大周期一律内存聚合，不再读写日线/5 分钟旧表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class CoreMarketBarService {

    private static final int BATCH_SIZE = 500;

    private final Market1MinMapper market1MinMapper;

    /** 是否已有 {@code market_1min} 数据。 */
    public boolean hasOneMin(String symbol) {
        return market1MinMapper.countBySymbol(symbol) > 0;
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
     * 从 {@code market_1min} 读取并按需聚合；无 1 分钟数据则返回空。
     */
    public List<BarDTO> load(String code, BarPeriod period, LocalDateTime start, LocalDateTime end) {
        if (period == null) {
            period = BarPeriod.DAY;
        }
        List<BarDTO> ones = loadOneMin(code, start, end);
        if (ones.isEmpty()) {
            return ones;
        }
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

    private List<BarDTO> loadOneMin(String code, LocalDateTime start, LocalDateTime end) {
        List<Market1MinDO> rows = market1MinMapper.selectRange(code, start, end);
        List<BarDTO> out = new ArrayList<BarDTO>(rows.size());
        for (Market1MinDO row : rows) {
            out.add(row.toBarDTO());
        }
        return out;
    }
}
