package com.quant.stock.market;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.mapper.Market1MinMapper;
import com.quant.stock.mapper.MarketDailyMapper;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.market.dto.Market1MinDO;
import com.quant.stock.market.dto.MarketDailyDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 核心行情读写：
 * <ul>
 *   <li>{@code market_1min} — 池内交易 / 分钟回测物理真相源；分钟及以上由分钟内存聚合</li>
 *   <li>{@code market_daily} — 全市场选股 / 扫池日线真相源；DAY（及周月派生）优先读此表</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class CoreMarketBarService {

    private static final int BATCH_SIZE = 500;

    private final Market1MinMapper market1MinMapper;
    private final MarketDailyMapper marketDailyMapper;
    private final JdbcTemplate jdbcTemplate;
    private final QuantProperties quantProperties;

    @PostConstruct
    public void ensureSchema() {
        ensureMarketDailyTable();
        ensureColumn("data_source",
                "ALTER TABLE `market_1min` ADD COLUMN `data_source` VARCHAR(16) NOT NULL DEFAULT 'TDX' "
                        + "COMMENT '行情来源: MOCK/TDX/MDS' AFTER `amount`");
        ensureColumn("ingested_at",
                "ALTER TABLE `market_1min` ADD COLUMN `ingested_at` DATETIME DEFAULT CURRENT_TIMESTAMP "
                        + "COMMENT '入库时间' AFTER `data_source`");
        ensureIndex("idx_data_source",
                "ALTER TABLE `market_1min` ADD KEY `idx_data_source` (`data_source`)");
        try {
            int n = jdbcTemplate.update(
                    "UPDATE `market_1min` SET `data_source`='TDX' "
                            + "WHERE `data_source` IS NULL OR TRIM(`data_source`)=''");
            if (n > 0) {
                log.info("market_1min 存量 data_source 标为 TDX，更新行数={}", n);
            }
        } catch (Exception e) {
            log.error("market_1min data_source 回填跳过: {}", e.getMessage(), e);
        }
    }

    private void ensureMarketDailyTable() {
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS `market_daily` ("
                            + "`id` BIGINT AUTO_INCREMENT PRIMARY KEY,"
                            + "`symbol` VARCHAR(10) NOT NULL,"
                            + "`trade_date` DATE NOT NULL,"
                            + "`open` DECIMAL(10,4) NOT NULL,"
                            + "`high` DECIMAL(10,4) NOT NULL,"
                            + "`low` DECIMAL(10,4) NOT NULL,"
                            + "`close` DECIMAL(10,4) NOT NULL,"
                            + "`volume` BIGINT NOT NULL,"
                            + "`amount` DECIMAL(16,4) DEFAULT NULL,"
                            + "`adj_flag` VARCHAR(8) NOT NULL DEFAULT 'NONE',"
                            + "`data_source` VARCHAR(16) NOT NULL DEFAULT 'TDX',"
                            + "`ingested_at` DATETIME DEFAULT CURRENT_TIMESTAMP,"
                            + "UNIQUE KEY `idx_symbol_date` (`symbol`, `trade_date`),"
                            + "KEY `idx_date` (`trade_date`),"
                            + "KEY `idx_data_source` (`data_source`)"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
                            + "COMMENT='日线行情(全市场选股真相源)'");
        } catch (Exception e) {
            log.error("ensure market_daily 失败: {}", e.getMessage(), e);
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
            log.error("market_1min ensureColumn {} 失败: {}", column, e.getMessage(), e);
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
            log.error("market_1min ensureIndex {} 失败: {}", indexName, e.getMessage(), e);
        }
    }

    /** 是否已有 {@code market_1min} 数据。 */
    public boolean hasOneMin(String symbol) {
        return market1MinMapper.countBySymbol(symbol) > 0;
    }

    /** 是否已有 {@code market_daily} 数据。 */
    public boolean hasDaily(String symbol) {
        return marketDailyMapper.countBySymbol(symbol) > 0;
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
     * 写入/更新 market_daily（默认 adj=NONE、来源 TDX）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int saveDaily(List<BarDTO> bars) {
        return saveDaily(bars, "NONE", MarketDataSources.TDX);
    }

    /**
     * 写入/更新 market_daily，并标记复权与来源。
     */
    @Transactional(rollbackFor = Exception.class)
    public int saveDaily(List<BarDTO> bars, String adjFlag, String dataSource) {
        if (bars == null || bars.isEmpty()) {
            return 0;
        }
        List<MarketDailyDO> list = new ArrayList<MarketDailyDO>(bars.size());
        for (BarDTO bar : bars) {
            MarketDailyDO row = MarketDailyDO.fromBarDTO(bar, adjFlag, dataSource);
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
     * 按周期加载：DAY/WEEK/MONTH 受 {@code quant.day-source} 控制（默认 auto：日线表优先，空则分钟聚日）；
     * 分钟周期只读 {@code market_1min}。
     */
    public List<BarDTO> load(String code, BarPeriod period, LocalDateTime start, LocalDateTime end) {
        if (period == null) {
            period = BarPeriod.DAY;
        }
        if (period == BarPeriod.DAY || period == BarPeriod.WEEK || period == BarPeriod.MONTH) {
            return loadDayFamily(code, period, start, end);
        }
        return loadFromOneMin(code, period, start, end);
    }

    private List<BarDTO> loadDayFamily(String code, BarPeriod period, LocalDateTime start, LocalDateTime end) {
        String mode = normalizeDaySource(quantProperties.getDaySource());
        if (!"aggregate".equals(mode)) {
            List<BarDTO> fromTable = loadDailyBars(code, start, end);
            if (!fromTable.isEmpty()) {
                if (period == BarPeriod.DAY) {
                    return fromTable;
                }
                return BarAggregateUtil.aggregate(fromTable, period.getAggregatePeriod());
            }
            if ("table".equals(mode)) {
                return new ArrayList<BarDTO>();
            }
        }
        return loadFromOneMin(code, period, start, end);
    }

    private List<BarDTO> loadFromOneMin(String code, BarPeriod period, LocalDateTime start, LocalDateTime end) {
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

    private List<BarDTO> loadDailyBars(String code, LocalDateTime start, LocalDateTime end) {
        LocalDate startDate = start == null ? null : start.toLocalDate();
        LocalDate endDate = end == null ? null : end.toLocalDate();
        List<MarketDailyDO> rows = marketDailyMapper.selectRange(code, startDate, endDate);
        List<BarDTO> out = new ArrayList<BarDTO>(rows.size());
        for (MarketDailyDO row : rows) {
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

    private static String normalizeDaySource(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "auto";
        }
        String v = raw.trim().toLowerCase();
        if ("table".equals(v) || "aggregate".equals(v) || "auto".equals(v)) {
            return v;
        }
        return "auto";
    }
}
