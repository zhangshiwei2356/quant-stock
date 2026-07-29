package com.quant.stock.strategy;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.strategy.dto.TradeSignal;

import java.util.List;

/**
 * 策略抽象基类；实现类在已收盘 K 线序列上产出 {@link TradeSignal}。
 * <p>
 * 稳定 id 见 {@link #name()}（配置 {@code quant.active-strategy}）；指纹兼容名见 {@link #fingerprintId()}。
 */
public abstract class BaseStrategy {

    /**
     * 策略稳定标识（配置/注册表用），如 {@code maCross}。
     */
    public abstract String name();

    /**
     * 写入配置指纹的策略名。默认与 {@link #name()} 相同；
     * 金叉策略覆写为历史值 {@code MaCrossStrategy} 以保持默认指纹不变。
     */
    public String fingerprintId() {
        return name();
    }

    /**
     * 回测/运维下拉展示名；默认等于 {@link #name()}。
     */
    public String uiLabel() {
        return name();
    }

    /**
     * 可选参数画像摘要（对照策略可覆写）。
     */
    public String profileSummary() {
        return "";
    }

    /**
     * 基于已闭合 K 线计算当前信号。
     *
     * @param stockCode  标的
     * @param closedBars 按时间升序的已收盘 bar（含当前最后一根）
     */
    public abstract TradeSignal calcSignal(String stockCode, List<BarDTO> closedBars);

    /**
     * 指定 bar 索引是否为买入信号（回测/实盘主循环用，与 {@link #calcSignal} 语义一致）。
     */
    public abstract boolean isBuySignalAt(IndicatorSignalUtil.IndicatorBundle ind, int i);

    /**
     * 指定 bar 索引是否为卖出信号（回测/实盘主循环用）。
     */
    public abstract boolean isSellSignalAt(IndicatorSignalUtil.IndicatorBundle ind, int i);
}
