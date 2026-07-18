package com.quant.stock.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 回测决策分析一条事件：信号/成交/拒绝/风控等。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisEvent {

    /** SIGNAL_BUY / SIGNAL_SELL / SIGNAL_PYRAMID / FILL_BUY / FILL_SELL / STOP / REJECT / EXPIRE / RISK */
    private String type;
    private String time;
    private String stockCode;
    private String title;
    /** 人类可读原因说明 */
    private String reason;
    /** 当时分析用到的关键数据 */
    @Builder.Default
    private Map<String, Object> data = new LinkedHashMap<String, Object>();
}
