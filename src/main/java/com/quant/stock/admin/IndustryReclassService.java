package com.quant.stock.admin;


import lombok.extern.slf4j.Slf4j;
import com.quant.stock.mapper.StockBasicMapper;
import com.quant.stock.market.dto.StockBasicDO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行业分类 reclass as-of 日志（P0-121）。
 * 中性化/归因用当时行业；不并金叉主路径。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class IndustryReclassService {

    private final JdbcTemplate jdbc;
    private final ObjectProvider<StockBasicMapper> stockBasicMapperProvider;

    public IndustryReclassService(JdbcTemplate jdbc,
                                  ObjectProvider<StockBasicMapper> stockBasicMapperProvider) {
        this.jdbc = jdbc;
        this.stockBasicMapperProvider = stockBasicMapperProvider;
    }

    /** 启动时建行业变更日志表，并在空表时用 stock_basic 快照打底。 */
    @PostConstruct
    public void init() {
        ensureTable();
        seedSnapshotIfEmpty();
    }

    /** 按 as-of 日取该标的当时行业（无日志时回退 stock_basic） */
    public String industryAsOf(String symbol, LocalDate asOf) {
        if (!StringUtils.hasText(symbol)) {
            return null;
        }
        LocalDate day = asOf == null ? LocalDate.now() : asOf;
        try {
            return jdbc.query(
                    "SELECT industry_to FROM industry_reclass_log WHERE symbol=? AND effective_date<=? "
                            + "ORDER BY effective_date DESC LIMIT 1",
                    rs -> rs.next() ? rs.getString(1) : null,
                    symbol.trim(), day);
        } catch (Exception e) {
            log.error("行业 reclass 异常", e);
            return snapshotIndustry(symbol.trim());
        }
    }

    /** 追加一条行业重分类 as-of 记录 */
    public Map<String, Object> logReclass(String symbol, LocalDate effectiveDate,
                                          String from, String to, String source, String note) {
        ensureTable();
        jdbc.update(
                "INSERT INTO industry_reclass_log(symbol, effective_date, industry_from, industry_to, source, note, created_at) "
                        + "VALUES(?,?,?,?,?,?,?)",
                symbol.trim(), effectiveDate, from, to,
                source == null ? "MANUAL" : source, note, LocalDateTime.now());
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("symbol", symbol);
        m.put("effectiveDate", effectiveDate.toString());
        m.put("from", from);
        m.put("to", to);
        return m;
    }

    /** 对照 stock_basic 现行业：若与最新日志不同则记一条 reclass（as-of=今天） */
    public Map<String, Object> syncFromStockBasic() {
        ensureTable();
        StockBasicMapper mapper = stockBasicMapperProvider.getIfAvailable();
        int logged = 0;
        if (mapper != null) {
            LocalDate today = LocalDate.now();
            List<StockBasicDO> all = mapper.selectAll();
            if (all != null) {
                for (StockBasicDO b : all) {
                    if (b == null || !StringUtils.hasText(b.getSymbol())) {
                        continue;
                    }
                    String cur = b.getIndustry() == null ? "" : b.getIndustry().trim();
                    String pit = industryAsOf(b.getSymbol(), today);
                    String prev = pit == null ? "" : pit.trim();
                    if (!cur.equals(prev)) {
                        logReclass(b.getSymbol(), today, prev.isEmpty() ? null : prev, cur,
                                "SYNC_STOCK_BASIC", "industry changed vs pit log");
                        logged++;
                    }
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("logged", logged);
        m.put("hint", "仅当现行业与 as-of 日志不一致时追加；无外部申万/中信重分类源");
        return m;
    }

    /** 服务状态与最近重分类记录 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("logRows", countRows());
        m.put("recent", recent(30));
        m.put("localSource", "stock_basic + industry_reclass_log");
        m.put("externalIndustrySource", "UNAVAILABLE");
        m.put("hint", "行业 reclass as-of 日志；选股中性用当时行业；无申万/中信外部源；不改金叉");
        return m;
    }

    /** 最近 {@code limit} 条行业重分类日志 */
    public List<Map<String, Object>> recent(int limit) {
        int lim = Math.max(1, Math.min(limit, 200));
        try {
            return jdbc.queryForList(
                    "SELECT symbol, effective_date AS effectiveDate, industry_from AS industryFrom, "
                            + "industry_to AS industryTo, source, note, created_at AS createdAt "
                            + "FROM industry_reclass_log ORDER BY effective_date DESC, id DESC LIMIT " + lim);
        } catch (Exception e) {
            log.error("行业 reclass 异常", e);
            return new ArrayList<Map<String, Object>>();
        }
    }

    private void ensureTable() {
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS `industry_reclass_log` ("
                        + "`id` BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "`symbol` VARCHAR(10) NOT NULL,"
                        + "`effective_date` DATE NOT NULL,"
                        + "`industry_from` VARCHAR(50) DEFAULT NULL,"
                        + "`industry_to` VARCHAR(50) DEFAULT NULL,"
                        + "`source` VARCHAR(40) DEFAULT NULL,"
                        + "`note` VARCHAR(200) DEFAULT NULL,"
                        + "`created_at` DATETIME DEFAULT NULL,"
                        + "KEY `idx_sym_eff` (`symbol`,`effective_date`),"
                        + "KEY `idx_eff` (`effective_date`)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行业重分类as-of日志(P0-121)'");
    }

    private void seedSnapshotIfEmpty() {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(1) FROM industry_reclass_log", Integer.class);
            if (n != null && n > 0) {
                return;
            }
            StockBasicMapper mapper = stockBasicMapperProvider.getIfAvailable();
            if (mapper == null) {
                return;
            }
            LocalDate today = LocalDate.now();
            List<StockBasicDO> all = mapper.selectAll();
            if (all == null) {
                return;
            }
            for (StockBasicDO b : all) {
                if (b == null || !StringUtils.hasText(b.getSymbol()) || !StringUtils.hasText(b.getIndustry())) {
                    continue;
                }
                jdbc.update(
                        "INSERT INTO industry_reclass_log(symbol, effective_date, industry_from, industry_to, source, note, created_at) "
                                + "VALUES(?,?,?,?,?,?,?)",
                        b.getSymbol().trim(), today, null, b.getIndustry().trim(),
                        "SEED_SNAPSHOT", "initial industry from stock_basic", LocalDateTime.now());
            }
        } catch (Exception ignored) {
            log.error("行业 reclass 异常", ignored);
            // empty
        }
    }

    private String snapshotIndustry(String symbol) {
        StockBasicMapper mapper = stockBasicMapperProvider.getIfAvailable();
        if (mapper == null) {
            return null;
        }
        try {
            for (StockBasicDO b : mapper.selectAll()) {
                if (b != null && symbol.equals(b.getSymbol())) {
                    return b.getIndustry();
                }
            }
        } catch (Exception ignored) {
            log.error("行业 reclass 异常", ignored);
            // empty
        }
        return null;
    }

    private int countRows() {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(1) FROM industry_reclass_log", Integer.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.error("行业 reclass 异常", e);
            return 0;
        }
    }
}
