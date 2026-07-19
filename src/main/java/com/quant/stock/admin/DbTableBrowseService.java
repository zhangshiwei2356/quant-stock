package com.quant.stock.admin;

import com.quant.stock.admin.DbTableCatalog.TableDef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 白名单库表分页浏览（只读）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class DbTableBrowseService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final int CELL_MAX_LEN = 500;

    /** 库表 COMMENT 缺失时的常用字段中文兜底 */
    private static final Map<String, String> FALLBACK_LABELS;

    static {
        Map<String, String> m = new HashMap<String, String>();
        m.put("id", "主键ID");
        m.put("symbol", "股票代码");
        m.put("name", "名称/简称");
        m.put("stock_code", "股票代码");
        m.put("market", "市场");
        m.put("industry", "所属行业");
        m.put("list_date", "上市日期");
        m.put("delist_date", "退市日期");
        m.put("is_st", "是否ST");
        m.put("status", "状态");
        m.put("trade_date", "交易日期");
        m.put("trade_time", "交易时间");
        m.put("open", "开盘价");
        m.put("high", "最高价");
        m.put("low", "最低价");
        m.put("close", "收盘价");
        m.put("volume", "成交量");
        m.put("amount", "成交额");
        m.put("turnover_rate", "换手率");
        m.put("limit_up", "涨停价");
        m.put("limit_down", "跌停价");
        m.put("ma5", "MA5");
        m.put("ma20", "MA20");
        m.put("ma60", "MA60");
        m.put("rsi14", "RSI14");
        m.put("atr14", "ATR14");
        m.put("adx", "ADX");
        m.put("volume_ma20", "成交量MA20");
        m.put("ma60_up", "MA60上行");
        m.put("is_volume_break", "放量突破");
        m.put("order_id", "订单号");
        m.put("account_id", "账户ID");
        m.put("signal_date", "信号日");
        m.put("execution_date", "成交日");
        m.put("order_type", "委托类型");
        m.put("stage", "加仓阶段");
        m.put("price", "委托价");
        m.put("filled_volume", "成交量");
        m.put("filled_price", "成交价");
        m.put("fee", "手续费");
        m.put("expire_date", "过期日");
        m.put("entry_date", "建仓日");
        m.put("current_volume", "持仓数量");
        m.put("avg_cost", "持仓成本");
        m.put("highest_price_since_entry", "入场后最高价");
        m.put("stop_price", "止损价");
        m.put("trail_price", "移动止盈价");
        m.put("open_date", "开仓日");
        m.put("cost", "成本");
        m.put("cash", "现金");
        m.put("market_value", "市值");
        m.put("total_equity", "总权益");
        m.put("peak_equity", "权益峰值");
        m.put("daily_pnl", "当日盈亏");
        m.put("daily_pnl_rate", "当日盈亏率");
        m.put("drawdown_rate", "回撤率");
        m.put("consecutive_loss_count", "连续亏损次数");
        m.put("config_key", "配置键");
        m.put("config_value", "配置值");
        m.put("type", "类型");
        m.put("description", "说明");
        m.put("log_date", "日志日期");
        m.put("rule_type", "规则类型");
        m.put("trigger_value", "触发值");
        m.put("action_taken", "采取动作");
        m.put("record_id", "记录ID");
        m.put("kind", "回测类型");
        m.put("saved_at", "保存时间");
        m.put("stock_codes_json", "股票列表JSON");
        m.put("period", "周期");
        m.put("back_start", "回测开始");
        m.put("back_end", "回测结束");
        m.put("init_capital", "初始资金");
        m.put("final_asset", "期末资产");
        m.put("total_rate", "总收益率");
        m.put("max_drawdown", "最大回撤");
        m.put("total_trade_num", "交易次数");
        m.put("win_rate", "胜率");
        m.put("trade_stats_json", "交易统计JSON");
        m.put("trades_json", "成交明细JSON");
        m.put("stock_results_json", "分股结果JSON");
        m.put("event_count", "事件数");
        m.put("summary", "摘要");
        m.put("events_json", "事件JSON");
        m.put("last_agg_time", "最近聚合时间");
        m.put("source_max_time", "源数据最大时间");
        m.put("score", "分数");
        m.put("reason", "原因");
        m.put("analysis_json", "分析JSON");
        m.put("batch_id", "批次ID");
        m.put("report_id", "报告ID");
        m.put("scanned_at", "扫描时间");
        m.put("source", "来源");
        m.put("entered_at", "进入时间");
        m.put("entered_final_at", "进入最终池时间");
        m.put("removed_at", "移出时间");
        m.put("job_code", "任务编码");
        m.put("job_name", "任务名称");
        m.put("trigger_type", "触发类型");
        m.put("cron_expr", "Cron表达式");
        m.put("interval_ms", "固定间隔(ms)");
        m.put("enabled", "是否启用");
        m.put("implemented", "是否已实现");
        m.put("last_run_at", "最近执行时间");
        m.put("remark", "备注");
        m.put("created_at", "创建时间");
        m.put("updated_at", "更新时间");
        FALLBACK_LABELS = Collections.unmodifiableMap(m);
    }

    private final JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> listTables() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (TableDef def : DbTableCatalog.all()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.putAll(tableMeta(def));
            try {
                Long cnt = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM `" + def.getName() + "`", Long.class);
                m.put("rowCount", cnt == null ? 0L : cnt);
                m.put("exists", true);
            } catch (Exception e) {
                m.put("rowCount", null);
                m.put("exists", false);
                m.put("error", e.getMessage());
            }
            list.add(m);
        }
        return list;
    }

    private static Map<String, Object> tableMeta(TableDef def) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", def.getName());
        m.put("title", def.getTitle());
        m.put("module", def.getModule());
        m.put("orderBy", def.getOrderBy());
        m.put("purpose", def.getPurpose());
        m.put("source", def.getSource());
        m.put("usage", def.getUsage());
        return m;
    }

    public Map<String, Object> page(String tableName, int page, int size) {
        TableDef def = DbTableCatalog.get(tableName);
        if (def == null) {
            throw new IllegalArgumentException("不在白名单中的表: " + tableName);
        }
        int p = Math.max(1, page);
        int s = size <= 0 ? DEFAULT_SIZE : Math.min(MAX_SIZE, size);
        int offset = (p - 1) * s;

        long total;
        try {
            Long cnt = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM `" + def.getName() + "`", Long.class);
            total = cnt == null ? 0L : cnt;
        } catch (Exception e) {
            throw new IllegalArgumentException("表不存在或不可访问: " + def.getName() + " (" + e.getMessage() + ")");
        }

        Map<String, String> commentByCol = loadColumnComments(def.getName());
        String sql = "SELECT * FROM `" + def.getName() + "` ORDER BY " + def.getOrderBy()
                + " LIMIT " + s + " OFFSET " + offset;

        final List<String> columnNames = new ArrayList<String>();
        List<Map<String, Object>> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            if (columnNames.isEmpty()) {
                for (int i = 1; i <= colCount; i++) {
                    columnNames.add(meta.getColumnLabel(i));
                }
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            for (int i = 1; i <= colCount; i++) {
                String col = meta.getColumnLabel(i);
                Object val = rs.getObject(i);
                row.put(col, stringifyCell(val));
            }
            return row;
        });

        if (columnNames.isEmpty()) {
            columnNames.addAll(commentByCol.keySet());
            if (columnNames.isEmpty()) {
                columnNames.addAll(loadColumnNames(def.getName()));
            }
        }

        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        for (String col : columnNames) {
            columns.add(toColumnMeta(col, commentByCol.get(col)));
        }

        long pages = s == 0 ? 0 : (total + s - 1) / s;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.putAll(tableMeta(def));
        out.put("page", p);
        out.put("size", s);
        out.put("total", total);
        out.put("pages", pages);
        out.put("columns", columns);
        out.put("rows", rows);
        return out;
    }

    private Map<String, Object> toColumnMeta(String name, String dbComment) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        String comment = dbComment == null ? "" : dbComment.trim();
        if (comment.isEmpty()) {
            String fb = FALLBACK_LABELS.get(name == null ? "" : name.toLowerCase(Locale.ROOT));
            comment = fb == null ? "" : fb;
        }
        m.put("comment", comment);
        m.put("label", comment.isEmpty() ? name : comment);
        return m;
    }

    /** 列名 → COMMENT（保持 ordinal 顺序用 LinkedHashMap） */
    private Map<String, String> loadColumnComments(String table) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT COLUMN_NAME, COLUMN_COMMENT FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                            + "ORDER BY ORDINAL_POSITION",
                    table);
            Map<String, String> map = new LinkedHashMap<String, String>();
            for (Map<String, Object> row : rows) {
                Object n = row.get("COLUMN_NAME");
                if (n == null) {
                    continue;
                }
                Object c = row.get("COLUMN_COMMENT");
                map.put(String.valueOf(n), c == null ? "" : String.valueOf(c));
            }
            return map;
        } catch (Exception e) {
            log.warn("读取列注释失败 {}: {}", table, e.getMessage());
            return new LinkedHashMap<String, String>();
        }
    }

    private List<String> loadColumnNames(String table) {
        try {
            return jdbcTemplate.query(
                    "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                            + "ORDER BY ORDINAL_POSITION",
                    (rs, i) -> rs.getString(1),
                    table);
        } catch (Exception e) {
            log.warn("读取列信息失败 {}: {}", table, e.getMessage());
            return new ArrayList<String>();
        }
    }

    private static Object stringifyCell(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof byte[]) {
            return "[BLOB " + ((byte[]) val).length + " bytes]";
        }
        String s = String.valueOf(val);
        if (s.length() > CELL_MAX_LEN) {
            return s.substring(0, CELL_MAX_LEN) + "…(" + s.length() + "字)";
        }
        return s;
    }
}
