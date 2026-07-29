package com.quant.stock.strategy;

import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.strategy.dto.TradeSignal;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 占位策略：永不产生买卖信号。用于验证 {@code quant.active-strategy} 切换与注册表，不改金叉实现。
 */
@Component
public class HoldNothingStrategy extends BaseStrategy {

    @Override
    public String name() {
        return "holdNothing";
    }

    @Override
    public String uiLabel() {
        return "永不交易占位（holdNothing）";
    }

    @Override
    public String profileSummary() {
        return "无买卖信号；用于切换验证";
    }

    @Override
    public TradeSignal calcSignal(String stockCode, List<BarDTO> closedBars) {
        return TradeSignal.none(stockCode);
    }

    @Override
    public boolean isBuySignalAt(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        return false;
    }

    @Override
    public boolean isSellSignalAt(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        return false;
    }
}
