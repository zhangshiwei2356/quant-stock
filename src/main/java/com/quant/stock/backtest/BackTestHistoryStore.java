package com.quant.stock.backtest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.quant.stock.backtest.dto.BackTestQueryDTO;
import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.backtest.dto.BackTestTradeStats;
import com.quant.stock.backtest.dto.BackTradeRecord;
import com.quant.stock.backtest.dto.BtBacktestRecordDO;
import com.quant.stock.backtest.dto.PortfolioBacktestHistoryRecord;
import com.quant.stock.backtest.dto.PortfolioResultDTO;
import com.quant.stock.backtest.dto.SingleBacktestHistoryRecord;
import com.quant.stock.backtest.dto.SingleStockBackResult;
import com.quant.stock.mapper.BacktestRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 回测历史：优先写入 MySQL {@code bt_backtest_record}（quant.db-enabled=true）。
 */
@Slf4j
@Service
public class BackTestHistoryStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<List<BackTradeRecord>> TRADE_TYPE =
            new TypeReference<List<BackTradeRecord>>() {};
    private static final TypeReference<List<String>> STR_LIST =
            new TypeReference<List<String>>() {};
    private static final TypeReference<List<SingleStockBackResult>> STOCK_RES_TYPE =
            new TypeReference<List<SingleStockBackResult>>() {};

    @Autowired(required = false)
    private BacktestRecordMapper backtestRecordMapper;

    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public BackTestHistoryStore(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    /** 启动时补齐 bt_backtest_record 可选列 */
    @PostConstruct
    public void ensureSchema() {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            return;
        }
        ensureColumn(jdbc, "bt_backtest_record", "config_fingerprint",
                "ALTER TABLE `bt_backtest_record` ADD COLUMN `config_fingerprint` VARCHAR(64) DEFAULT NULL "
                        + "COMMENT '策略配置指纹 P0-93' AFTER `stock_results_json`");
        ensureColumn(jdbc, "bt_backtest_record", "strategy_id",
                "ALTER TABLE `bt_backtest_record` ADD COLUMN `strategy_id` VARCHAR(64) DEFAULT NULL "
                        + "COMMENT '注册策略 id' AFTER `config_fingerprint`");
        ensureColumn(jdbc, "bt_backtest_record", "sharpe",
                "ALTER TABLE `bt_backtest_record` ADD COLUMN `sharpe` DECIMAL(12,6) DEFAULT NULL "
                        + "COMMENT '年化夏普 RF=0 展示用' AFTER `win_rate`");
        ensureIndex(jdbc, "bt_backtest_record", "idx_strategy_saved",
                "CREATE INDEX idx_strategy_saved ON bt_backtest_record (strategy_id, saved_at)");
    }

    /** 持久化单股回测结果并返回历史视图 */
    public SingleBacktestHistoryRecord appendSingle(String period, String backStart, String backEnd,
                                                    BackTestResult result, String strategyId) {
        if (result == null) {
            return null;
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        String savedAt = LocalDateTime.now().format(FMT);
        String sid = emptyToNull(strategyId);
        SingleBacktestHistoryRecord rec = SingleBacktestHistoryRecord.fromResult(
                id, savedAt, period, emptyToNull(backStart), emptyToNull(backEnd), result, sid);
        if (backtestRecordMapper == null) {
            log.info("未启用 MySQL，单股回测历史未持久化 id={}", id);
            return rec;
        }
        BtBacktestRecordDO row = BtBacktestRecordDO.builder()
                .recordId(id)
                .kind("SINGLE")
                .savedAt(LocalDateTime.parse(savedAt, FMT))
                .stockCode(result.getStockCode())
                .period(period)
                .backStart(emptyToNull(backStart))
                .backEnd(emptyToNull(backEnd))
                .initCapital(result.getInitCapital())
                .finalAsset(result.getFinalAsset())
                .totalRate(result.getTotalRate())
                .maxDrawdown(result.getMaxDrawDown())
                .totalTradeNum(result.getTotalTradeNum())
                .winRate(result.getWinRate())
                .sharpe(rec.getSharpe())
                .tradeStatsJson(JSON.toJSONString(rec.getTradeStats()))
                .tradesJson(JSON.toJSONString(rec.getTrades()))
                .configFingerprint(result.getConfigFingerprint())
                .strategyId(sid)
                .build();
        backtestRecordMapper.insert(row);
        return rec;
    }

    /** 持久化组合回测结果 */
    public PortfolioBacktestHistoryRecord appendPortfolio(BackTestQueryDTO query, PortfolioResultDTO result,
                                                          String strategyId) {
        if (result == null) {
            return null;
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        String savedAt = LocalDateTime.now().format(FMT);
        String sid = emptyToNull(strategyId);
        PortfolioBacktestHistoryRecord rec = PortfolioBacktestHistoryRecord.fromResult(
                id, savedAt, query, result, sid);
        if (backtestRecordMapper == null) {
            log.info("未启用 MySQL，组合回测历史未持久化 id={}", id);
            return rec;
        }
        BtBacktestRecordDO row = BtBacktestRecordDO.builder()
                .recordId(id)
                .kind("PORTFOLIO")
                .savedAt(LocalDateTime.parse(savedAt, FMT))
                .stockCode(null)
                .stockCodesJson(query == null ? null : JSON.toJSONString(query.getStockCodeList()))
                .period("DAY")
                .backStart(query == null || query.getBackStart() == null ? null
                        : query.getBackStart().format(FMT))
                .backEnd(query == null || query.getBackEnd() == null ? null
                        : query.getBackEnd().format(FMT))
                .initCapital(result.getInitCapital())
                .finalAsset(result.getFinalAsset())
                .totalRate(result.getTotalRate())
                .maxDrawdown(result.getMaxDrawDown())
                .totalTradeNum(result.getTotalTradeNum())
                .winRate(result.getWinRate())
                .sharpe(rec.getSharpe())
                .tradeStatsJson(JSON.toJSONString(rec.getTradeStats()))
                .tradesJson(JSON.toJSONString(rec.getTrades()))
                .stockResultsJson(JSON.toJSONString(rec.getStockResults()))
                .configFingerprint(result.getConfigFingerprint())
                .strategyId(sid)
                .build();
        backtestRecordMapper.insert(row);
        return rec;
    }

    /**
     * 按策略 id 查摘要历史（不含 trades / stock_results）。
     * {@code kind} 为 null/blank/ALL 时不过滤类型。
     */
    public List<?> listSummaryByStrategy(String strategyId, String kind) {
        if (backtestRecordMapper == null || !StringUtils.hasText(strategyId)) {
            return Collections.emptyList();
        }
        String kindFilter = null;
        if (StringUtils.hasText(kind)) {
            String k = kind.trim().toUpperCase();
            if (!"ALL".equals(k)) {
                kindFilter = k;
            }
        }
        List<BtBacktestRecordDO> rows = backtestRecordMapper.selectSummaryByStrategyId(
                strategyId.trim(), kindFilter);
        List<Object> out = new ArrayList<Object>();
        for (BtBacktestRecordDO r : rows) {
            if ("PORTFOLIO".equalsIgnoreCase(r.getKind())) {
                out.add(toPortfolio(r));
            } else {
                out.add(toSingle(r));
            }
        }
        return out;
    }

    /**
     * 按注册策略 id（含历史别名）查摘要历史。
     */
    public List<?> listSummaryByStrategyIds(List<String> strategyIds, String kind) {
        if (backtestRecordMapper == null || strategyIds == null || strategyIds.isEmpty()) {
            return Collections.emptyList();
        }
        String kindFilter = null;
        if (StringUtils.hasText(kind)) {
            String k = kind.trim().toUpperCase();
            if (!"ALL".equals(k)) {
                kindFilter = k;
            }
        }
        List<BtBacktestRecordDO> rows = backtestRecordMapper.selectSummaryByStrategyIds(
                strategyIds, kindFilter);
        List<Object> out = new ArrayList<Object>();
        for (BtBacktestRecordDO r : rows) {
            if ("PORTFOLIO".equalsIgnoreCase(r.getKind())) {
                out.add(toPortfolio(r));
            } else {
                out.add(toSingle(r));
            }
        }
        return out;
    }

    /** 按 recordId 取全量单股或组合历史视图；不存在返回 null。 */
    public Object getByRecordId(String recordId) {
        if (backtestRecordMapper == null || !StringUtils.hasText(recordId)) {
            return null;
        }
        BtBacktestRecordDO r = backtestRecordMapper.selectByRecordId(recordId.trim());
        if (r == null) {
            return null;
        }
        if ("PORTFOLIO".equalsIgnoreCase(r.getKind())) {
            return toPortfolio(r);
        }
        return toSingle(r);
    }

    /** 按标的过滤单股历史（空则全部） */
    public List<SingleBacktestHistoryRecord> listSingle(String stockCode) {
        if (backtestRecordMapper == null) {
            return Collections.emptyList();
        }
        List<BtBacktestRecordDO> rows = backtestRecordMapper.selectByKind("SINGLE",
                StringUtils.hasText(stockCode) ? stockCode.trim() : null);
        List<SingleBacktestHistoryRecord> out = new ArrayList<SingleBacktestHistoryRecord>();
        for (BtBacktestRecordDO r : rows) {
            out.add(toSingle(r));
        }
        return out;
    }

    /** 列出全部组合回测历史 */
    public List<PortfolioBacktestHistoryRecord> listPortfolio() {
        if (backtestRecordMapper == null) {
            return Collections.emptyList();
        }
        List<BtBacktestRecordDO> rows = backtestRecordMapper.selectByKind("PORTFOLIO", null);
        List<PortfolioBacktestHistoryRecord> out = new ArrayList<PortfolioBacktestHistoryRecord>();
        for (BtBacktestRecordDO r : rows) {
            out.add(toPortfolio(r));
        }
        return out;
    }

    /** 删除指定标的的单股历史条数 */
    public int clearSingleByCode(String stockCode) {
        if (backtestRecordMapper == null || !StringUtils.hasText(stockCode)) {
            return 0;
        }
        return backtestRecordMapper.deleteSingleByCode(stockCode.trim());
    }

    /** 清空全部组合回测历史 */
    public int clearAllPortfolio() {
        if (backtestRecordMapper == null) {
            return 0;
        }
        return backtestRecordMapper.deleteAllByKind("PORTFOLIO");
    }

    private SingleBacktestHistoryRecord toSingle(BtBacktestRecordDO r) {
        List<BackTradeRecord> trades = parseTrades(r.getTradesJson());
        BackTestTradeStats stats = r.getTradeStatsJson() == null ? null
                : JSON.parseObject(r.getTradeStatsJson(), BackTestTradeStats.class);
        if (stats == null) {
            stats = BackTestTradeStats.from(trades, r.getInitCapital(), r.getFinalAsset());
        }
        return SingleBacktestHistoryRecord.builder()
                .id(r.getRecordId())
                .savedAt(r.getSavedAt() == null ? null : r.getSavedAt().format(FMT))
                .stockCode(r.getStockCode())
                .period(r.getPeriod())
                .backStart(r.getBackStart())
                .backEnd(r.getBackEnd())
                .initCapital(r.getInitCapital())
                .finalAsset(r.getFinalAsset())
                .totalRate(r.getTotalRate())
                .maxDrawDown(r.getMaxDrawdown())
                .totalTradeNum(r.getTotalTradeNum())
                .winRate(r.getWinRate())
                .sharpe(r.getSharpe())
                .tradeStats(stats)
                .trades(trades)
                .configFingerprint(r.getConfigFingerprint())
                .strategyId(r.getStrategyId())
                .build();
    }

    private PortfolioBacktestHistoryRecord toPortfolio(BtBacktestRecordDO r) {
        List<BackTradeRecord> trades = parseTrades(r.getTradesJson());
        BackTestTradeStats stats = r.getTradeStatsJson() == null ? null
                : JSON.parseObject(r.getTradeStatsJson(), BackTestTradeStats.class);
        if (stats == null) {
            stats = BackTestTradeStats.from(trades, r.getInitCapital(), r.getFinalAsset());
        }
        List<String> codes = r.getStockCodesJson() == null ? null
                : JSON.parseObject(r.getStockCodesJson(), STR_LIST);
        List<SingleStockBackResult> stockResults = r.getStockResultsJson() == null
                ? new ArrayList<SingleStockBackResult>()
                : JSON.parseObject(r.getStockResultsJson(), STOCK_RES_TYPE);
        return PortfolioBacktestHistoryRecord.builder()
                .id(r.getRecordId())
                .savedAt(r.getSavedAt() == null ? null : r.getSavedAt().format(FMT))
                .stockCodeList(codes)
                .backStart(r.getBackStart())
                .backEnd(r.getBackEnd())
                .initCapital(r.getInitCapital())
                .finalAsset(r.getFinalAsset())
                .totalRate(r.getTotalRate())
                .maxDrawDown(r.getMaxDrawdown())
                .totalTradeNum(r.getTotalTradeNum())
                .winRate(r.getWinRate())
                .sharpe(r.getSharpe())
                .tradeStats(stats)
                .stockResults(stockResults == null ? new ArrayList<SingleStockBackResult>() : stockResults)
                .trades(trades)
                .configFingerprint(r.getConfigFingerprint())
                .strategyId(r.getStrategyId())
                .build();
    }

    private List<BackTradeRecord> parseTrades(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<BackTradeRecord>();
        }
        List<BackTradeRecord> list = JSON.parseObject(json, TRADE_TYPE);
        return list == null ? new ArrayList<BackTradeRecord>() : list;
    }

    private static String emptyToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private void ensureColumn(JdbcTemplate jdbc, String table, String column, String alterSql) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (n == null || n == 0) {
                jdbc.execute(alterSql);
                log.info("已补齐列 {}.{}", table, column);
            }
        } catch (Exception e) {
            log.error("检查/补齐列 {}.{} 失败: {}", table, column, e.getMessage(), e);
        }
    }

    /** 若索引不存在则创建（查 information_schema.STATISTICS，避免每次启动撞已存在错误） */
    private void ensureIndex(JdbcTemplate jdbc, String table, String indexName, String createSql) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.STATISTICS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                    Integer.class, table, indexName);
            if (n == null || n == 0) {
                jdbc.execute(createSql);
                log.info("已补齐索引 {}.{}", table, indexName);
            }
        } catch (Exception e) {
            log.error("检查/补齐索引 {}.{} 失败: {}", table, indexName, e.getMessage(), e);
        }
    }
}
