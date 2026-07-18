package com.quant.stock.strategy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSignal {

    public enum Signal {
        NONE, BUY, SELL
    }

    private String stockCode;
    private Signal signalType;
    private BigDecimal suggestPrice;
    private Integer suggestVolume;
    private String signalDesc;

    public static TradeSignal none(String code) {
        return TradeSignal.builder()
                .stockCode(code)
                .signalType(Signal.NONE)
                .suggestVolume(0)
                .signalDesc("无信号")
                .build();
    }
}
