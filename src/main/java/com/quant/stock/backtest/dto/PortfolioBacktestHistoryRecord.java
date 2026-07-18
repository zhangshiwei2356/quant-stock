package com.quant.stock.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 组合回测历史一条（落盘 JSON）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioBacktestHistoryRecord {

    private String id;
    private String savedAt;
    private List<String> stockCodeList;
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
    private List<SingleStockBackResult> stockResults;
    private List<BackTradeRecord> trades;

    public static PortfolioBacktestHistoryRecord fromResult(String id, String savedAt,
                                                            BackTestQueryDTO query,
                                                            PortfolioResultDTO result) {
        List<String> codes = query == null || query.getStockCodeList() == null
                ? new ArrayList<String>() : new ArrayList<String>(query.getStockCodeList());
        String start = query != null && query.getBackStart() != null
                ? query.getBackStart().toString().replace('T', ' ') : null;
        String end = query != null && query.getBackEnd() != null
                ? query.getBackEnd().toString().replace('T', ' ') : null;
        List<BackTradeRecord> tradeList = result.getTrades() == null
                ? new ArrayList<BackTradeRecord>() : result.getTrades();
        return PortfolioBacktestHistoryRecord.builder()
                .id(id)
                .savedAt(savedAt)
                .stockCodeList(codes)
                .backStart(start)
                .backEnd(end)
                .initCapital(result.getInitCapital())
                .finalAsset(result.getFinalAsset())
                .totalRate(result.getTotalRate())
                .maxDrawDown(result.getMaxDrawDown())
                .totalTradeNum(result.getTotalTradeNum())
                .winRate(result.getWinRate())
                .tradeStats(BackTestTradeStats.from(tradeList, result.getInitCapital(), result.getFinalAsset()))
                .stockResults(result.getStockResults() == null
                        ? new ArrayList<SingleStockBackResult>() : result.getStockResults())
                .trades(tradeList)
                .build();
    }
}
