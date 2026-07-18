package com.quant.stock.market;

import com.quant.stock.mapper.BarAggregateMetaMapper;
import com.quant.stock.mapper.StockBarMapper;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.market.dto.StockBarDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * K线分表存储：1min 唯一写入入口；大周期由聚合任务生成
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class BarStorageService {

    private static final int BATCH_SIZE = 500;

    private final StockBarMapper stockBarMapper;
    private final BarAggregateMetaMapper barAggregateMetaMapper;

    /** 原始层写入：仅允许写 1 分钟表 */
    @Transactional(rollbackFor = Exception.class)
    public int save1MinBars(List<BarDTO> bars) {
        if (bars == null || bars.isEmpty()) {
            return 0;
        }
        List<StockBarDO> list = new ArrayList<StockBarDO>(bars.size());
        for (BarDTO bar : bars) {
            list.add(StockBarDO.fromBarDTO(bar));
        }
        return batchUpsert(BarPeriod.MIN_1.getTableName(), list);
    }

    public List<BarDTO> loadBars(String code, BarPeriod period, LocalDateTime start, LocalDateTime end) {
        List<StockBarDO> rows = stockBarMapper.selectRange(period.getTableName(), code, start, end);
        List<BarDTO> result = new ArrayList<BarDTO>(rows.size());
        for (StockBarDO row : rows) {
            result.add(row.toBarDTO());
        }
        return result;
    }

    public boolean hasBars(String code, BarPeriod period, LocalDateTime start, LocalDateTime end) {
        return stockBarMapper.countRange(period.getTableName(), code, start, end) > 0;
    }

    public LocalDateTime maxBarTime(String code, BarPeriod period) {
        return stockBarMapper.selectMaxBarTime(period.getTableName(), code);
    }

    /**
     * 从 1 分钟表聚合写入指定周期表（可按时间窗增量）
     */
    @Transactional(rollbackFor = Exception.class)
    public int aggregateFrom1Min(String code, BarPeriod period, LocalDateTime start, LocalDateTime end) {
        if (period == null || period.isRaw()) {
            return 0;
        }
        List<BarDTO> minuteBars = loadBars(code, BarPeriod.MIN_1, start, end);
        if (minuteBars.isEmpty()) {
            return 0;
        }
        List<BarDTO> agg = BarAggregateUtil.aggregate(minuteBars, period.getAggregatePeriod());
        if (agg.isEmpty()) {
            return 0;
        }
        List<StockBarDO> rows = new ArrayList<StockBarDO>(agg.size());
        for (BarDTO bar : agg) {
            rows.add(StockBarDO.fromBarDTO(bar));
        }
        int n = batchUpsert(period.getTableName(), rows);
        LocalDateTime sourceMax = minuteBars.get(minuteBars.size() - 1).getBarBegin();
        LocalDateTime lastAgg = agg.get(agg.size() - 1).getBarBegin();
        barAggregateMetaMapper.upsertMeta(code, period.name(), lastAgg, sourceMax);
        log.info("聚合完成 code={} period={} bars={}", code, period, n);
        return n;
    }

    /** 聚合全部预置周期（收盘清算用） */
    @Transactional(rollbackFor = Exception.class)
    public void aggregateAllPeriods(String code, LocalDateTime start, LocalDateTime end) {
        for (BarPeriod period : BarPeriod.aggregatePeriods()) {
            // 版本校验：1min 未更新则跳过
            LocalDateTime sourceMax = maxBarTime(code, BarPeriod.MIN_1);
            LocalDateTime metaMax = barAggregateMetaMapper.selectSourceMaxTime(code, period.name());
            if (sourceMax != null && metaMax != null && !sourceMax.isAfter(metaMax)) {
                log.debug("跳过聚合(无新增1min) code={} period={}", code, period);
                continue;
            }
            aggregateFrom1Min(code, period, start, end);
        }
    }

    /** 修复：删除聚合表区间后从1min重建 */
    @Transactional(rollbackFor = Exception.class)
    public int rebuildPeriod(String code, BarPeriod period, LocalDateTime start, LocalDateTime end) {
        if (period.isRaw()) {
            throw new IllegalArgumentException("不能重建原始层1分钟表，请重新拉行情");
        }
        stockBarMapper.deleteRange(period.getTableName(), code, start, end);
        return aggregateFrom1Min(code, period, start, end);
    }

    private int batchUpsert(String tableName, List<StockBarDO> list) {
        int total = 0;
        for (int i = 0; i < list.size(); i += BATCH_SIZE) {
            int to = Math.min(i + BATCH_SIZE, list.size());
            total += stockBarMapper.batchUpsert(tableName, list.subList(i, to));
        }
        return total;
    }
}
