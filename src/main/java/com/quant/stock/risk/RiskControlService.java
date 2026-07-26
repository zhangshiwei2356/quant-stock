package com.quant.stock.risk;

import com.quant.stock.calendar.TradingCalendar;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.BarAggregateUtil;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.trade.dto.OrderDTO;
import com.quant.stock.util.PositionAmountUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 风控：仓位、资金、交易时间、开仓过滤、账户熔断
 */
@Service
@RequiredArgsConstructor
public class RiskControlService {

    private final QuantProperties quantProperties;
    private final PositionAmountUtil positionAmountUtil;
    private final OpenFilterService openFilterService;
    private final LiveAccountRiskState accountRiskState;
    private final TradingCalendar tradingCalendar;
    private final StrategyRetirementService strategyRetirementService;

    /** 是否在 A 股交易时段且非静默期 */
    public boolean isTradingTime(LocalDateTime now) {
        if (now == null || !tradingCalendar.isAshareTradingDay(now.toLocalDate())) {
            return false;
        }
        return BarAggregateUtil.isTradingMinute(now) && !openFilterService.isQuietPeriod(now);
    }

    /** 买入风控（墙钟时间；委托 {@link #checkBuy} 完整版） */
    public boolean checkBuy(String stockCode,
                            BigDecimal price,
                            int volume,
                            BigDecimal totalCash,
                            BigDecimal totalPositionValue,
                            Map<String, Integer> positions) {
        return checkBuy(stockCode, price, volume, totalCash, totalPositionValue, positions, null, -1);
    }

    /**
     * 买入风控：交易时段、策略退役、账户熔断、开仓过滤、资金与单票/总仓上限。
     *
     * @param bars  可选 K 线（回测/历史 bar 用 bar 时间判定交易时段）
     * @param index 当前 bar 下标；无则 -1
     */
    public boolean checkBuy(String stockCode,
                            BigDecimal price,
                            int volume,
                            BigDecimal totalCash,
                            BigDecimal totalPositionValue,
                            Map<String, Integer> positions,
                            List<BarDTO> bars,
                            int index) {
        if (volume <= 0 || volume % 100 != 0) {
            return false;
        }
        // 优先用 K 线时间（回测/盘后扫历史 bar），无则退回墙钟
        LocalDateTime clock = null;
        if (bars != null && index >= 0 && index < bars.size() && bars.get(index) != null) {
            clock = bars.get(index).getBarBegin();
        }
        if (clock == null) {
            clock = LocalDateTime.now();
        }
        if (!isTradingTime(clock)) {
            return false;
        }
        LocalDate tradeDay = clock.toLocalDate();
        BigDecimal equity = totalCash.add(totalPositionValue);
        if (!strategyRetirementService.allowNewOpen()) {
            return false;
        }
        if (!accountRiskState.allowNewOpen(tradeDay, equity)) {
            return false;
        }
        if (bars != null && index >= 0 && !openFilterService.canOpen(stockCode, bars, index)) {
            return false;
        }
        BigDecimal cost = price.multiply(BigDecimal.valueOf(volume));
        if (cost.compareTo(totalCash) > 0) {
            return false;
        }
        if (!positionAmountUtil.withinTotalPosition(equity, totalPositionValue, cost)) {
            return false;
        }
        int held = positions == null ? 0 : positions.getOrDefault(stockCode, 0);
        BigDecimal singleMv = price.multiply(BigDecimal.valueOf(held + volume));
        BigDecimal maxSingle = equity.multiply(quantProperties.getMaxSinglePosition())
                .multiply(accountRiskState.positionScale(equity));
        return singleMv.compareTo(maxSingle) <= 0;
    }

    /** 卖出风控：整手、交易时段、停牌拒卖、持仓充足 */
    public boolean checkSell(String stockCode, int volume, Map<String, Integer> positions) {
        return checkSell(stockCode, volume, positions, null, -1);
    }

    /** 卖出风控（可传入 K 线判定时间与停牌） */
    public boolean checkSell(String stockCode,
                             int volume,
                             Map<String, Integer> positions,
                             List<BarDTO> bars,
                             int index) {
        if (volume <= 0 || volume % 100 != 0) {
            return false;
        }
        LocalDateTime clock = null;
        if (bars != null && index >= 0 && index < bars.size() && bars.get(index) != null) {
            clock = bars.get(index).getBarBegin();
        }
        if (clock == null) {
            clock = LocalDateTime.now();
        }
        if (!isTradingTime(clock)) {
            return false;
        }
        if (bars != null && index >= 0 && index < bars.size()
                && openFilterService.isSuspended(bars.get(index))) {
            return false;
        }
        int held = positions == null ? 0 : positions.getOrDefault(stockCode, 0);
        return held >= volume;
    }

    /** 不通过风控则返回 null，否则原样返回订单 */
    public OrderDTO filterOrder(OrderDTO order, BigDecimal totalCash, BigDecimal totalPositionValue,
                                Map<String, Integer> positions) {
        if (order.getSide() == OrderDTO.Side.BUY) {
            if (!checkBuy(order.getStockCode(), order.getPrice(), order.getVolume(),
                    totalCash, totalPositionValue, positions)) {
                return null;
            }
        } else if (!checkSell(order.getStockCode(), order.getVolume(), positions)) {
            return null;
        }
        return order;
    }
}
