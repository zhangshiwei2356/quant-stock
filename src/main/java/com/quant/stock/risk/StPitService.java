package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
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
 * ST as-of 日切（P0-101）：优先 {@code st_status_hist}；无历史则回退 {@code stock_basic.is_st} 快照。
 * 财报公告时钟本地无数据，见 {@link #earningsClockStatus()}。
 */
@Service
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class StPitService {

    private final QuantProperties props;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<StockBasicMapper> stockBasicMapperProvider;

    public StPitService(QuantProperties props, JdbcTemplate jdbc,
                        ObjectProvider<StockBasicMapper> stockBasicMapperProvider) {
        this.props = props;
        this.jdbc = jdbc;
        this.stockBasicMapperProvider = stockBasicMapperProvider;
    }

    /** 启动时建表并从 stock_basic 种子化 ST 历史 */
    @PostConstruct
    public void init() {
        ensureTable();
        seedFromSnapshotIfEmpty();
    }

    public boolean isStAsOf(String code, LocalDate asOf) {
        if (!StringUtils.hasText(code)) {
            return false;
        }
        LocalDate day = asOf == null ? LocalDate.now() : asOf;
        try {
            Integer v = jdbc.query(
                    "SELECT is_st FROM st_status_hist WHERE symbol=? AND effective_date<=? "
                            + "ORDER BY effective_date DESC LIMIT 1",
                    rs -> rs.next() ? rs.getInt(1) : null,
                    code.trim(), day);
            if (v != null) {
                return v == 1;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return snapshotIsSt(code.trim());
    }

    /** 运维/对账用：ST 开仓过滤开关、日切表行数、数据源优先级与财报时钟边界。 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("stOpenFilterEnabled", props.isStOpenFilterEnabled());
        m.put("histRows", countHist());
        m.put("sourcePriority", "st_status_hist as-of → stock_basic.is_st snapshot");
        m.put("earningsClock", earningsClockStatus());
        m.put("hint", "ST 日切表可手工/导入写入；无行时回退现状态（有前视风险）");
        return m;
    }

    /** 财报/静默期时钟能力说明（本地未接数据源时固定不可用）。 */
    public Map<String, Object> earningsClockStatus() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("available", false);
        m.put("reason", "本地无财报公告日/静默期时钟数据源");
        m.put("boundary", "PEAD/财报事件属沙箱；不进金叉主路径");
        return m;
    }

    /** 运维写入一条 ST 日切 */
    public Map<String, Object> upsert(String symbol, LocalDate effectiveDate, boolean isSt, String note) {
        ensureTable();
        jdbc.update(
                "INSERT INTO st_status_hist(symbol, effective_date, is_st, note, updated_at) VALUES(?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE is_st=VALUES(is_st), note=VALUES(note), updated_at=VALUES(updated_at)",
                symbol.trim(), effectiveDate, isSt ? 1 : 0, note, LocalDateTime.now());
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("symbol", symbol);
        m.put("effectiveDate", effectiveDate.toString());
        m.put("isSt", isSt);
        return m;
    }

    /** 查询最近 ST 日切记录 */
    public List<Map<String, Object>> recent(int limit) {
        int lim = Math.max(1, Math.min(limit, 200));
        try {
            return jdbc.queryForList(
                    "SELECT symbol, effective_date AS effectiveDate, is_st AS isSt, note, updated_at AS updatedAt "
                            + "FROM st_status_hist ORDER BY effective_date DESC, id DESC LIMIT " + lim);
        } catch (Exception e) {
            return new ArrayList<Map<String, Object>>();
        }
    }

    private void ensureTable() {
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS `st_status_hist` ("
                        + "`id` BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "`symbol` VARCHAR(10) NOT NULL,"
                        + "`effective_date` DATE NOT NULL COMMENT '该日起生效的ST状态',"
                        + "`is_st` TINYINT NOT NULL DEFAULT 0,"
                        + "`note` VARCHAR(200) DEFAULT NULL,"
                        + "`updated_at` DATETIME DEFAULT NULL,"
                        + "UNIQUE KEY `uk_sym_eff` (`symbol`,`effective_date`),"
                        + "KEY `idx_eff` (`effective_date`)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ST状态日切(P0-101)'");
    }

    private void seedFromSnapshotIfEmpty() {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(1) FROM st_status_hist", Integer.class);
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
                if (b == null || !StringUtils.hasText(b.getSymbol())) {
                    continue;
                }
                int st = b.getIsSt() != null && b.getIsSt() == 1 ? 1 : 0;
                jdbc.update(
                        "INSERT IGNORE INTO st_status_hist(symbol, effective_date, is_st, note, updated_at) "
                                + "VALUES(?,?,?,?,?)",
                        b.getSymbol().trim(), today, st, "seed_from_stock_basic_snapshot", LocalDateTime.now());
            }
        } catch (Exception ignored) {
            // empty
        }
    }

    private boolean snapshotIsSt(String code) {
        StockBasicMapper mapper = stockBasicMapperProvider.getIfAvailable();
        if (mapper == null) {
            return false;
        }
        try {
            List<StockBasicDO> all = mapper.selectAll();
            if (all == null) {
                return false;
            }
            for (StockBasicDO b : all) {
                if (b != null && code.equals(b.getSymbol()) && b.getIsSt() != null && b.getIsSt() == 1) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // empty
        }
        return false;
    }

    private int countHist() {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(1) FROM st_status_hist", Integer.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }
}
