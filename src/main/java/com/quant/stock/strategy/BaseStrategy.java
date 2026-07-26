package com.quant.stock.strategy;

import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.strategy.dto.TradeSignal;

import java.util.List;

/**
 * 策略抽象基类；实现类在已收盘 K 线序列上产出 {@link TradeSignal}。
 */
public abstract class BaseStrategy {

    /** 策略唯一标识（配置/日志用）。 */
    public abstract String name();

    /**
     * 基于已闭合 K 线计算当前信号。
     *
     * @param stockCode   标的
     * @param closedBars  按时间升序的已收盘 bar（含当前最后一根）
     */
    public abstract TradeSignal calcSignal(String stockCode, List<BarDTO> closedBars);
}
