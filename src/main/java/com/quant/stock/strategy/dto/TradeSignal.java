package com.quant.stock.strategy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 策略输出信号：方向、建议价量及可读说明（实际下单量由风控/仓位模块计算）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSignal {

    /** 信号方向 */
    public enum Signal {
        NONE, BUY, SELL
    }

    /** 标的代码 */
    private String stockCode;
    private Signal signalType;
    /** 建议成交价（通常为最新收盘） */
    private BigDecimal suggestPrice;
    /** 建议数量；主路径常由仓位模块另行计算，可为 0 */
    private Integer suggestVolume;
    /** 人类可读信号说明 */
    private String signalDesc;

    /** 构造「无信号」占位结果。 */
    public static TradeSignal none(String code) {
        return TradeSignal.builder()
                .stockCode(code)
                .signalType(Signal.NONE)
                .suggestVolume(0)
                .signalDesc("无信号")
                .build();
    }
}
