package com.quant.stock.account;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账户落库只读查询：委托、权益日结、风控事件。
 */
@Service
@ConditionalOnProperty(prefix = "quant", name = "db-enabled", havingValue = "true")
public class AccountLedgerQueryService {

    private static final String ACCOUNT_ID = "LIVE";

    private final JdbcTemplate jdbc;

    public AccountLedgerQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listOrders(int limit) {
        int lim = Math.max(1, Math.min(limit <= 0 ? 200 : limit, 500));
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT order_id, symbol, signal_date, execution_date, order_type, stage, price, volume, "
                            + "filled_volume, filled_price, fee, status, created_at, updated_at "
                            + "FROM trade_orders WHERE account_id=? ORDER BY id DESC LIMIT ?",
                    ACCOUNT_ID, lim);
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("orderId", r.get("order_id"));
                m.put("code", r.get("symbol"));
                m.put("signalDate", toDateStr(r.get("signal_date")));
                m.put("executionDate", toDateStr(r.get("execution_date")));
                m.put("orderType", r.get("order_type"));
                m.put("stage", r.get("stage"));
                m.put("price", toBd(r.get("price")));
                int vol = r.get("volume") == null ? 0 : ((Number) r.get("volume")).intValue();
                int filled = r.get("filled_volume") == null ? 0 : ((Number) r.get("filled_volume")).intValue();
                m.put("volume", vol);
                m.put("filledVolume", filled);
                m.put("filledPrice", toBd(r.get("filled_price")));
                m.put("fee", toBd(r.get("fee")));
                m.put("statusCode", r.get("status"));
                m.put("status", mapOrderStatus(r.get("status")));
                m.put("side", mapOrderSide(r.get("order_type")));
                BigDecimal px = toBd(r.get("filled_price"));
                if (px.compareTo(BigDecimal.ZERO) <= 0) {
                    px = toBd(r.get("price"));
                }
                m.put("amount", px.multiply(BigDecimal.valueOf(filled > 0 ? filled : vol)));
                m.put("createdAt", toDateTimeStr(r.get("created_at")));
                m.put("updatedAt", toDateTimeStr(r.get("updated_at")));
                m.put("source", "DB");
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<Map<String, Object>>();
        }
    }

    private static String mapOrderStatus(Object code) {
        if (code == null) {
            return "PENDING";
        }
        int c = ((Number) code).intValue();
        switch (c) {
            case 2:
                return "SUBMITTED";
            case 3:
                return "PARTIAL";
            case 4:
                return "FILLED";
            case 5:
                return "CANCELLED";
            case 6:
                return "REJECTED";
            default:
                return "PENDING";
        }
    }

    /** order_type: 1 买入类，4+ 卖出类（与 LiveLedgerService 写入约定一致） */
    private static String mapOrderSide(Object orderType) {
        if (orderType == null) {
            return null;
        }
        int t = ((Number) orderType).intValue();
        return t >= 4 ? "SELL" : "BUY";
    }

    public List<Map<String, Object>> listCashflows(int limit) {
        int lim = Math.max(1, Math.min(limit <= 0 ? 120 : limit, 500));
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT trade_date, cash, market_value, total_equity, peak_equity, daily_pnl, daily_pnl_rate, "
                            + "drawdown_rate, consecutive_loss_count "
                            + "FROM trade_cashflows WHERE account_id=? ORDER BY trade_date DESC LIMIT ?",
                    ACCOUNT_ID, lim);
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("tradeDate", toDateStr(r.get("trade_date")));
                m.put("cash", toBd(r.get("cash")));
                m.put("marketValue", toBd(r.get("market_value")));
                m.put("totalEquity", toBd(r.get("total_equity")));
                m.put("peakEquity", toBd(r.get("peak_equity")));
                m.put("dailyPnl", toBd(r.get("daily_pnl")));
                m.put("dailyPnlRate", toBd(r.get("daily_pnl_rate")));
                m.put("drawdownRate", toBd(r.get("drawdown_rate")));
                m.put("consecutiveLossCount", r.get("consecutive_loss_count") == null
                        ? 0 : ((Number) r.get("consecutive_loss_count")).intValue());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<Map<String, Object>>();
        }
    }

    public List<Map<String, Object>> listRiskLogs(int limit) {
        int lim = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 500));
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, log_date, symbol, rule_type, trigger_value, action_taken, created_at "
                            + "FROM risk_control_log WHERE account_id=? ORDER BY id DESC LIMIT ?",
                    ACCOUNT_ID, lim);
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("id", r.get("id"));
                m.put("logDate", toDateStr(r.get("log_date")));
                m.put("symbol", r.get("symbol"));
                m.put("ruleType", r.get("rule_type"));
                m.put("triggerValue", toBd(r.get("trigger_value")));
                m.put("actionTaken", r.get("action_taken"));
                m.put("createdAt", toDateTimeStr(r.get("created_at")));
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<Map<String, Object>>();
        }
    }

    private static BigDecimal toBd(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal) {
            return (BigDecimal) o;
        }
        return new BigDecimal(o.toString());
    }

    private static String toDateStr(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Date) {
            return ((Date) o).toLocalDate().toString();
        }
        if (o instanceof LocalDate) {
            return o.toString();
        }
        if (o instanceof java.util.Date) {
            return new Date(((java.util.Date) o).getTime()).toLocalDate().toString();
        }
        return o.toString();
    }

    private static String toDateTimeStr(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Timestamp) {
            return ((Timestamp) o).toLocalDateTime().toString();
        }
        if (o instanceof LocalDateTime) {
            return o.toString();
        }
        return o.toString();
    }
}
