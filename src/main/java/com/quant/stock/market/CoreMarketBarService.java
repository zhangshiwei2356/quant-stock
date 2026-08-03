package com.quant.stock.market;

import com.quant.stock.mapper.Market1MinMapper;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.market.dto.Market1MinDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
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
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureDataSourceColumns() {
        ensureColumn("data_source",
                "ALTER TABLE `market_1min` ADD COLUMN `data_source` VARCHAR(16) NOT NULL DEFAULT 'TDX' "
                        + "COMMENT '行情来源: MOCK/TDX/MDS' AFTER `amount`");
        ensureColumn("ingested_at",
                "ALTER TABLE `market_1min` ADD COLUMN `ingested_at` DATETIME DEFAULT CURRENT_TIMESTAMP "
                        + "COMMENT '入库时间' AFTER `data_source`");
        ensureIndex("idx_data_source",
                "ALTER TABLE `market_1min` ADD KEY `idx_data_source` (`data_source`)");
        // 存量空值标为通达信
        try {
            int n = jdbcTemplate.update(
                    "UPDATE `market_1min` SET `data_source`='TDX' "
                            + "WHERE `data_source` IS NULL OR TRIM(`data_source`)=''");
            if (n > 0) {
                log.info("market_1min 存量 data_source 标为 TDX，更新行数={}", n);
            }
        } catch (Exception e) {
            log.warn("market_1min data_source 回填跳过: {}", e.getMessage());
        }
    }

    private void ensureColumn(String column, String alterSql) {
        try {
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'market_1min' AND COLUMN_NAME = ?",
                    Integer.class, column);
            if (cnt != null && cnt > 0) {
                return;
            }
            jdbcTemplate.execute(alterSql);
            log.info("market_1min 已增加列 {}", column);
        } catch (Exception e) {
            log.warn("market_1min ensureColumn {} 失败: {}", column, e.getMessage());
        }
    }

    private void ensureIndex(String indexName, String alterSql) {
        try {
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.STATISTICS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'market_1min' AND INDEX_NAME = ?",
                    Integer.class, indexName);
            if (cnt != null && cnt > 0) {
                return;
            }
            jdbcTemplate.execute(alterSql);
            log.info("market_1min 已增加索引 {}", indexName);
        } catch (Exception e) {
            log.warn("market_1min ensureIndex {} 失败: {}", indexName, e.getMessage());
        }
    }

    /** 是否已有 {@code market_1min} 数据。 */
    public boolean hasOneMin(String symbol) {
        return market1MinMapper.countBySymbol(symbol) > 0;
    }

    /**
     * 写入/更新 market_1min（默认来源 {@link MarketDataSources#TDX}）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int saveMinutes1(List<BarDTO> bars) {
        return saveMinutes1(bars, MarketDataSources.TDX);
    }

    /**
     * 写入/更新 market_1min，并标记 {@code data_source}。
     */
    @Transactional(rollbackFor = Exception.class)
    public int saveMinutes1(List<BarDTO> bars, String dataSource) {
        if (bars == null || bars.isEmpty()) {
            return 0;
        }
        String src = MarketDataSources.normalize(dataSource);
        List<Market1MinDO> list = new ArrayList<Market1MinDO>(bars.size());
        for (BarDTO bar : bars) {
            Market1MinDO row = Market1MinDO.fromBarDTO(bar, src);
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
