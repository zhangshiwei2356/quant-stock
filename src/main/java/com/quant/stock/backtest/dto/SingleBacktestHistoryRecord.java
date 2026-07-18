package com.quant.stock.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 单股回测历史一条（落盘 JSON，不含权益曲线以免文件过大）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingleBacktestHistoryRecord {

    private String id;
    private String savedAt;
    private String stockCode;
    private String period;
    private String backStart;
    private String backEnd;
    private BigDecimal initCapital;
    private BigDecimal finalAsset;
    private BigDecimal totalRate;
    private BigDecimal maxDrawDown;
    private Integer totalTradeNum;
    private BigDecimal winRate;
    /** 买卖次数/股数/手数/金额/费用/总盈亏 */
    private BackTestTradeStats tradeStats;
    private List<BackTradeRecord> trades;

    public static SingleBacktestHistoryRecord fromResult(String id, String savedAt,
                                                         String period, String backStart, String backEnd,
                                                         BackTestResult result) {
        List<BackTradeRecord> tradeList = result.getTrades() == null
                ? new ArrayList<BackTradeRecord>() : result.getTrades();
        return SingleBacktestHistoryRecord.builder()
                .id(id)
                .savedAt(savedAt)
                .stockCode(result.getStockCode())
                .period(period)
                .backStart(backStart)
                .backEnd(backEnd)
                .initCapital(result.getInitCapital())
                .finalAsset(result.getFinalAsset())
                .totalRate(result.getTotalRate())
                .maxDrawDown(result.getMaxDrawDown())
                .totalTradeNum(result.getTotalTradeNum())
                .winRate(result.getWinRate())
                .tradeStats(BackTestTradeStats.from(tradeList, result.getInitCapital(), result.getFinalAsset()))
                .trades(tradeList)
                .build();
    }
}
