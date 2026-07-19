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
        m.put("hint", "本地模拟账本（非券商柜台）。券商同步任务未接入前，数据以进程内 StrategyTask / TradeGateway 为准。");
        m.put("asOf", LocalDateTime.now().toString());
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
        m.put("orderCount", tradeGatewayService.listOrders().size());
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

    public List<Map<String, Object>> orders() {
        Map<String, String> names = nameMap();
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
