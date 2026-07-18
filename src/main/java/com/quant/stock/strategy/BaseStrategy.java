package com.quant.stock.strategy;

import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.strategy.dto.TradeSignal;

import java.util.List;

/**
 * 策略抽象基类，可扩展 MACD / 布林突破 / RSI 反转等
 */
public abstract class BaseStrategy {

    public abstract String name();

    public abstract TradeSignal calcSignal(String stockCode, List<BarDTO> closedBars);
}
