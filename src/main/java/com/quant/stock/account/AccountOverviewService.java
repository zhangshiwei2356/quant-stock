package com.quant.stock.account;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.mapper.StockBasicMapper;
import com.quant.stock.market.dto.StockBasicDO;
import com.quant.stock.risk.LiveAccountRiskState;
import com.quant.stock.task.StrategyTask;
import com.quant.stock.trade.TradeGatewayService;
import com.quant.stock.trade.dto.OrderDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账户概览：本地模拟账本（StrategyTask + TradeGateway）只读汇总。
 */
@Service
@RequiredArgsConstructor
public class AccountOverviewService {

    private final StrategyTask strategyTask;
    private final TradeGatewayService tradeGatewayService;
    private final LiveAccountRiskState accountRiskState;
    private final QuantProperties quantProperties;
    private final ObjectProvider<StockBasicMapper> stockBasicMapperProvider;
    private final ObjectProvider<AccountLedgerQueryService> ledgerQueryProvider;

    public Map<String, Object> summary() {
        BigDecimal cash = nz(strategyTask.getSimCash());
        BigDecimal posMv = nz(strategyTask.getPositionMarketValue());
        BigDecimal equity = nz(strategyTask.getMarkEquity());
        BigDecimal peak = nz(accountRiskState.getPeakEquity());
        BigDecimal prevClose = nz(accountRiskState.getPrevCloseEquity());
        BigDecimal dd = nz(accountRiskState.drawdown(equity));
        BigDecimal dayPnl = BigDecimal.ZERO;
        BigDecimal dayPnlPct = BigDecimal.ZERO;
        if (prevClose.compareTo(BigDecimal.ZERO) > 0) {
            dayPnl = equity.subtract(prevClose);
            dayPnlPct = dayPnl.divide(prevClose, 6, RoundingMode.HALF_UP);
        }
        BigDecimal init = new BigDecimal("100000");
        BigDecimal totalReturn = equity.subtract(init).divide(init, 6, RoundingMode.HALF_UP);

        List<Map<String, Object>> positions = positions();
        int posCount = positions.size();

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("mode", quantProperties.getTradeMode());
        m.put("source", "LOCAL_SIM");
        m.put("hint", "本地模拟账本（非券商柜台）。成交/持仓/现金已落库，重启可恢复；券商对账见待办清单。");
        m.put("asOf", LocalDateTime.now().toString());
        m.put("dbEnabled", quantProperties.isDbEnabled());
        m.put("cash", cash);
        m.put("positionMv", posMv);
        m.put("equity", equity);
        m.put("peakEquity", peak);
        m.put("prevCloseEquity", prevClose);
        m.put("drawdown", dd);
        m.put("dayPnl", dayPnl);
        m.put("dayPnlPct", dayPnlPct);
        m.put("totalReturn", totalReturn);
        m.put("initCapital", init);
        m.put("halted", accountRiskState.isHalted());
        m.put("consecutiveLosses", accountRiskState.getConsecutiveLosses());
        m.put("positionScale", accountRiskState.positionScale(equity));
        m.put("allowNewOpen", accountRiskState.allowNewOpen(LocalDate.now(), equity));
        m.put("positionCount", posCount);
        m.put("orderCount", orders().size());
        return m;
    }

    public List<Map<String, Object>> positions() {
        Map<String, String> names = nameMap();
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : strategyTask.listLivePositionViews()) {
            String code = String.valueOf(row.get("code"));
            row.put("name", names.getOrDefault(code, code));
            list.add(row);
        }
        return list;
    }

    /**
     * 委托列表：优先读库 trade_orders（重启可恢复）；库空时回退内存网关。
     */
    public List<Map<String, Object>> orders() {
        Map<String, String> names = nameMap();
        AccountLedgerQueryService q = ledgerQueryProvider.getIfAvailable();
        if (q != null) {
            List<Map<String, Object>> db = q.listOrders(200);
            if (!db.isEmpty()) {
                for (Map<String, Object> m : db) {
                    String code = String.valueOf(m.get("code"));
                    m.put("name", names.getOrDefault(code, code));
                }
                return db;
            }
        }
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (OrderDTO o : tradeGatewayService.listOrders()) {
            if (o == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("orderId", o.getOrderId());
            m.put("clientOrderId", o.getClientOrderId());
            m.put("code", o.getStockCode());
            m.put("name", names.getOrDefault(o.getStockCode(), o.getStockCode()));
            m.put("side", o.getSide() == null ? null : o.getSide().name());
            m.put("price", o.getPrice());
            m.put("volume", o.getVolume());
            m.put("status", o.getStatus() == null ? null : o.getStatus().name());
            m.put("amount", o.getPrice() != null && o.getVolume() != null
                    ? o.getPrice().multiply(BigDecimal.valueOf(o.getVolume())) : null);
            m.put("fee", null);
            m.put("signalDate", null);
            m.put("executionDate", null);
            m.put("source", "MEMORY");
            list.add(m);
        }
        return list;
    }

    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        Map<String, Object> sum = summary();
        m.putAll(sum);
        m.put("positions", positions());
        m.put("orders", orders());
        return m;
    }

    /** 收盘权益日结（trade_cashflows），按日倒序 */
    public Map<String, Object> cashflows(int limit) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        AccountLedgerQueryService q = ledgerQueryProvider.getIfAvailable();
        List<Map<String, Object>> items = q == null
                ? new ArrayList<Map<String, Object>>() : q.listCashflows(limit);
        m.put("dbEnabled", quantProperties.isDbEnabled());
        m.put("count", items.size());
        m.put("items", items);
        m.put("hint", q == null
                ? "未启用数据库（quant.db-enabled=false），无权益日结落库。"
                : "来自 settle-after-close 写入的 trade_cashflows；需先跑过收盘清算才有数据。");
        // 曲线按时间正序，便于前端画图
        List<String> times = new ArrayList<String>();
        List<BigDecimal> curve = new ArrayList<BigDecimal>();
        for (int i = items.size() - 1; i >= 0; i--) {
            Map<String, Object> row = items.get(i);
            times.add(String.valueOf(row.get("tradeDate")));
            Object eq = row.get("totalEquity");
            curve.add(eq instanceof BigDecimal ? (BigDecimal) eq
                    : (eq == null ? BigDecimal.ZERO : new BigDecimal(eq.toString())));
        }
        m.put("equityTimes", times);
        m.put("equityCurve", curve);
        return m;
    }

    /** 风控触发事件（risk_control_log） */
    public Map<String, Object> riskLogs(int limit) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        AccountLedgerQueryService q = ledgerQueryProvider.getIfAvailable();
        List<Map<String, Object>> items = q == null
                ? new ArrayList<Map<String, Object>>() : q.listRiskLogs(limit);
        m.put("dbEnabled", quantProperties.isDbEnabled());
        m.put("count", items.size());
        m.put("items", items);
        m.put("hint", q == null
                ? "未启用数据库（quant.db-enabled=false），无风控日志落库。"
                : "来自 risk_control_log（如峰值回撤熔断）；未触发时列表为空。");
        return m;
    }

    private Map<String, String> nameMap() {
        Map<String, String> names = new HashMap<String, String>();
        StockBasicMapper mapper = stockBasicMapperProvider.getIfAvailable();
        if (mapper != null) {
            try {
                List<StockBasicDO> all = mapper.selectAll();
                if (all != null) {
                    for (StockBasicDO b : all) {
                        if (b.getSymbol() != null) {
                            names.put(b.getSymbol(), b.getName() == null ? b.getSymbol() : b.getName());
                        }
                    }
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return names;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
