package com.quant.stock.trade;

import com.quant.stock.backtest.PositionState;
import com.quant.stock.trade.dto.OrderDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地模拟账本落库：委托、持仓/批次、权益日表、现金余额（system_config）。
 */
@Service
@ConditionalOnProperty(prefix = "quant", name = "db-enabled", havingValue = "true")
public class LiveLedgerService {

    private static final Logger log = LoggerFactory.getLogger(LiveLedgerService.class);
    public static final String ACCOUNT_ID = "LIVE";
    public static final String CASH_KEY = "sim.cash";

    private final JdbcTemplate jdbc;

    public LiveLedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal loadCashOrNull() {
        try {
            List<String> rows = jdbc.query(
                    "SELECT config_value FROM system_config WHERE config_key=?",
                    (rs, i) -> rs.getString(1),
                    CASH_KEY);
            if (rows.isEmpty() || rows.get(0) == null || rows.get(0).trim().isEmpty()) {
                return null;
            }
            return new BigDecimal(rows.get(0).trim());
        } catch (Exception e) {
            log.warn("读取模拟现金失败: {}", e.getMessage());
            return null;
        }
    }

    public void saveCash(BigDecimal cash) {
        if (cash == null) {
            return;
        }
        try {
            jdbc.update(
                    "INSERT INTO system_config(config_key, config_value, type, description) VALUES (?,?,1,?) "
                            + "ON DUPLICATE KEY UPDATE config_value=VALUES(config_value), updated_at=CURRENT_TIMESTAMP",
                    CASH_KEY, cash.toPlainString(), "本地模拟现金余额");
        } catch (Exception e) {
            log.warn("保存模拟现金失败: {}", e.getMessage());
        }
    }

    public void upsertOrder(OrderDTO order, LocalDate signalDate, BigDecimal fee) {
        if (order == null || order.getOrderId() == null) {
            return;
        }
        try {
            LocalDate day = signalDate == null ? LocalDate.now() : signalDate;
            int orderType = order.getSide() == OrderDTO.Side.SELL ? 4 : 1;
            int status = mapStatus(order.getStatus());
            int vol = order.getVolume() == null ? 0 : order.getVolume();
            int filled = order.getStatus() == OrderDTO.Status.FILLED
                    || order.getStatus() == OrderDTO.Status.SUBMITTED ? vol : 0;
            jdbc.update(
                    "INSERT INTO trade_orders(order_id, account_id, symbol, signal_date, execution_date, order_type, "
                            + "stage, price, volume, filled_volume, filled_price, fee, status) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE filled_volume=VALUES(filled_volume), filled_price=VALUES(filled_price), "
                            + "fee=VALUES(fee), status=VALUES(status), execution_date=VALUES(execution_date), "
                            + "updated_at=CURRENT_TIMESTAMP",
                    order.getOrderId(),
                    ACCOUNT_ID,
                    order.getStockCode(),
                    Date.valueOf(day),
                    filled > 0 ? Date.valueOf(day) : null,
                    orderType,
                    0,
                    order.getPrice(),
                    vol,
                    filled,
                    filled > 0 ? order.getPrice() : null,
                    fee,
                    status);
        } catch (Exception e) {
            log.warn("保存委托失败 {}: {}", order.getOrderId(), e.getMessage());
        }
    }

    public void upsertPosition(String symbol, PositionState pos) {
        if (symbol == null) {
            return;
        }
        try {
            if (pos == null || !pos.hasPosition()) {
                jdbc.update("DELETE FROM trade_position_lots WHERE account_id=? AND symbol=?", ACCOUNT_ID, symbol);
                jdbc.update("DELETE FROM trade_positions WHERE account_id=? AND symbol=?", ACCOUNT_ID, symbol);
                return;
            }
            LocalDate entry = pos.getLastBuyDate() == null ? LocalDate.now().minusDays(1) : pos.getLastBuyDate();
            jdbc.update(
                    "INSERT INTO trade_positions(account_id, symbol, entry_date, current_volume, avg_cost, "
                            + "highest_price_since_entry, stop_price, trail_price) "
                            + "VALUES (?,?,?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE entry_date=VALUES(entry_date), current_volume=VALUES(current_volume), "
                            + "avg_cost=VALUES(avg_cost), highest_price_since_entry=VALUES(highest_price_since_entry), "
                            + "stop_price=VALUES(stop_price), trail_price=VALUES(trail_price), updated_at=CURRENT_TIMESTAMP",
                    ACCOUNT_ID,
                    symbol,
                    Date.valueOf(entry),
                    pos.getShares(),
                    pos.getAvgCost(),
                    pos.getHighestSinceEntry(),
                    pos.getStopPrice(),
                    pos.getStopPrice());
            jdbc.update("DELETE FROM trade_position_lots WHERE account_id=? AND symbol=?", ACCOUNT_ID, symbol);
            for (PositionState.LotView lot : pos.snapshotLots()) {
                jdbc.update(
                        "INSERT INTO trade_position_lots(account_id, symbol, open_date, volume, cost) VALUES (?,?,?,?,?)",
                        ACCOUNT_ID,
                        symbol,
                        Date.valueOf(lot.openDate),
                        lot.shares,
                        lot.cost);
            }
        } catch (Exception e) {
            log.warn("保存持仓失败 {}: {}", symbol, e.getMessage());
        }
    }

    public Map<String, PositionState> loadPositions() {
        Map<String, PositionState> map = new HashMap<String, PositionState>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT symbol, current_volume, avg_cost, entry_date, stop_price, highest_price_since_entry "
                            + "FROM trade_positions WHERE account_id=? AND current_volume > 0",
                    ACCOUNT_ID);
            for (Map<String, Object> r : rows) {
                String symbol = String.valueOf(r.get("symbol"));
                PositionState ps = new PositionState();
                List<Map<String, Object>> lotRows = jdbc.queryForList(
                        "SELECT open_date, volume, cost FROM trade_position_lots WHERE account_id=? AND symbol=? "
                                + "ORDER BY open_date ASC, id ASC",
                        ACCOUNT_ID, symbol);
                if (!lotRows.isEmpty()) {
                    List<PositionState.LotView> lots = new ArrayList<PositionState.LotView>();
                    for (Map<String, Object> lr : lotRows) {
                        lots.add(new PositionState.LotView(
                                toDate(lr.get("open_date")),
                                ((Number) lr.get("volume")).intValue(),
                                toBd(lr.get("cost"))));
                    }
                    ps.restoreLots(lots, toBd(r.get("stop_price")), toBd(r.get("highest_price_since_entry")));
                } else {
                    ps.restoreSnapshot(
                            ((Number) r.get("current_volume")).intValue(),
                            toBd(r.get("avg_cost")),
                            toDate(r.get("entry_date")) == null ? LocalDate.now().minusDays(1) : toDate(r.get("entry_date")),
                            toBd(r.get("stop_price")),
                            toBd(r.get("highest_price_since_entry")));
                }
                if (ps.hasPosition()) {
                    map.put(symbol, ps);
                }
            }
        } catch (Exception e) {
            log.warn("加载持仓失败: {}", e.getMessage());
        }
        return map;
    }

    /** 收盘权益日表（按日 upsert） */
    public void upsertDailyCashflow(LocalDate tradeDate, BigDecimal cash, BigDecimal marketValue,
                                    BigDecimal totalEquity, BigDecimal peakEquity,
                                    BigDecimal dailyPnl, BigDecimal dailyPnlRate,
                                    BigDecimal drawdownRate, int consecutiveLossCount) {
        if (tradeDate == null) {
            return;
        }
        try {
            jdbc.update(
                    "INSERT INTO trade_cashflows(account_id, trade_date, cash, market_value, total_equity, peak_equity, "
                            + "daily_pnl, daily_pnl_rate, drawdown_rate, consecutive_loss_count) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE cash=VALUES(cash), market_value=VALUES(market_value), "
                            + "total_equity=VALUES(total_equity), peak_equity=VALUES(peak_equity), "
                            + "daily_pnl=VALUES(daily_pnl), daily_pnl_rate=VALUES(daily_pnl_rate), "
                            + "drawdown_rate=VALUES(drawdown_rate), consecutive_loss_count=VALUES(consecutive_loss_count)",
                    ACCOUNT_ID,
                    Date.valueOf(tradeDate),
                    cash,
                    marketValue,
                    totalEquity,
                    peakEquity,
                    dailyPnl,
                    dailyPnlRate,
                    drawdownRate,
                    consecutiveLossCount);
        } catch (Exception e) {
            log.warn("保存权益日表失败: {}", e.getMessage());
        }
    }

    private static int mapStatus(OrderDTO.Status status) {
        if (status == null) {
            return 1;
        }
        switch (status) {
            case SUBMITTED:
                return 2;
            case PARTIAL:
                return 3;
            case FILLED:
                return 4;
            case CANCELLED:
                return 5;
            case REJECTED:
                return 6;
            case PENDING:
            default:
                return 1;
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

    private static LocalDate toDate(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Date) {
            return ((Date) o).toLocalDate();
        }
        if (o instanceof java.util.Date) {
            return new Date(((java.util.Date) o).getTime()).toLocalDate();
        }
        return LocalDate.parse(o.toString());
    }
}
