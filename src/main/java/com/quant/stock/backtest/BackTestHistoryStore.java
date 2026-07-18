package com.quant.stock.backtest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.quant.stock.backtest.dto.BackTestQueryDTO;
import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.backtest.dto.BackTestTradeStats;
import com.quant.stock.backtest.dto.BackTradeRecord;
import com.quant.stock.backtest.dto.BtBacktestRecordDO;
import com.quant.stock.backtest.dto.PortfolioBacktestHistoryRecord;
import com.quant.stock.backtest.dto.PortfolioResultDTO;
import com.quant.stock.backtest.dto.SingleBacktestHistoryRecord;
import com.quant.stock.backtest.dto.SingleStockBackResult;
import com.quant.stock.mapper.BacktestRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 回测历史：优先写入 MySQL {@code bt_backtest_record}（quant.db-enabled=true）。
 */
@Slf4j
@Service
public class BackTestHistoryStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<List<BackTradeRecord>> TRADE_TYPE =
            new TypeReference<List<BackTradeRecord>>() {};
    private static final TypeReference<List<String>> STR_LIST =
            new TypeReference<List<String>>() {};
    private static final TypeReference<List<SingleStockBackResult>> STOCK_RES_TYPE =
            new TypeReference<List<SingleStockBackResult>>() {};

    @Autowired(required = false)
    private BacktestRecordMapper backtestRecordMapper;

    public SingleBacktestHistoryRecord appendSingle(String period, String backStart, String backEnd,
                                                    BackTestResult result) {
        if (result == null) {
            return null;
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        String savedAt = LocalDateTime.now().format(FMT);
        SingleBacktestHistoryRecord rec = SingleBacktestHistoryRecord.fromResult(
                id, savedAt, period, emptyToNull(backStart), emptyToNull(backEnd), result);
        if (backtestRecordMapper == null) {
            log.warn("未启用 MySQL，单股回测历史未持久化 id={}", id);
            return rec;
        }
        BtBacktestRecordDO row = BtBacktestRecordDO.builder()
                .recordId(id)
                .kind("SINGLE")
                .savedAt(LocalDateTime.parse(savedAt, FMT))
                .stockCode(result.getStockCode())
                .period(period)
                .backStart(emptyToNull(backStart))
                .backEnd(emptyToNull(backEnd))
                .initCapital(result.getInitCapital())
                .finalAsset(result.getFinalAsset())
                .totalRate(result.getTotalRate())
                .maxDrawdown(result.getMaxDrawDown())
                .totalTradeNum(result.getTotalTradeNum())
                .winRate(result.getWinRate())
                .tradeStatsJson(JSON.toJSONString(rec.getTradeStats()))
                .tradesJson(JSON.toJSONString(rec.getTrades()))
                .build();
        backtestRecordMapper.insert(row);
        return rec;
    }

    public PortfolioBacktestHistoryRecord appendPortfolio(BackTestQueryDTO query, PortfolioResultDTO result) {
        if (result == null) {
            return null;
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        String savedAt = LocalDateTime.now().format(FMT);
        PortfolioBacktestHistoryRecord rec = PortfolioBacktestHistoryRecord.fromResult(
                id, savedAt, query, result);
        if (backtestRecordMapper == null) {
            log.warn("未启用 MySQL，组合回测历史未持久化 id={}", id);
            return rec;
        }
        BtBacktestRecordDO row = BtBacktestRecordDO.builder()
                .recordId(id)
                .kind("PORTFOLIO")
                .savedAt(LocalDateTime.parse(savedAt, FMT))
                .stockCode(null)
                .stockCodesJson(query == null ? null : JSON.toJSONString(query.getStockCodeList()))
                .period("DAY")
                .backStart(query == null || query.getBackStart() == null ? null
                        : query.getBackStart().format(FMT))
                .backEnd(query == null || query.getBackEnd() == null ? null
                        : query.getBackEnd().format(FMT))
                .initCapital(result.getInitCapital())
                .finalAsset(result.getFinalAsset())
                .totalRate(result.getTotalRate())
                .maxDrawdown(result.getMaxDrawDown())
                .totalTradeNum(result.getTotalTradeNum())
                .winRate(result.getWinRate())
                .tradeStatsJson(JSON.toJSONString(rec.getTradeStats()))
                .tradesJson(JSON.toJSONString(rec.getTrades()))
                .stockResultsJson(JSON.toJSONString(rec.getStockResults()))
                .build();
        backtestRecordMapper.insert(row);
        return rec;
    }

    public List<SingleBacktestHistoryRecord> listSingle(String stockCode) {
        if (backtestRecordMapper == null) {
            return Collections.emptyList();
        }
        List<BtBacktestRecordDO> rows = backtestRecordMapper.selectByKind("SINGLE",
                StringUtils.hasText(stockCode) ? stockCode.trim() : null);
        List<SingleBacktestHistoryRecord> out = new ArrayList<SingleBacktestHistoryRecord>();
        for (BtBacktestRecordDO r : rows) {
            out.add(toSingle(r));
        }
        return out;
    }

    public List<PortfolioBacktestHistoryRecord> listPortfolio() {
        if (backtestRecordMapper == null) {
            return Collections.emptyList();
        }
        List<BtBacktestRecordDO> rows = backtestRecordMapper.selectByKind("PORTFOLIO", null);
        List<PortfolioBacktestHistoryRecord> out = new ArrayList<PortfolioBacktestHistoryRecord>();
        for (BtBacktestRecordDO r : rows) {
            out.add(toPortfolio(r));
        }
        return out;
    }

    public int clearSingleByCode(String stockCode) {
        if (backtestRecordMapper == null || !StringUtils.hasText(stockCode)) {
            return 0;
        }
        return backtestRecordMapper.deleteSingleByCode(stockCode.trim());
    }

    public int clearAllPortfolio() {
        if (backtestRecordMapper == null) {
            return 0;
        }
        return backtestRecordMapper.deleteAllByKind("PORTFOLIO");
    }

    private SingleBacktestHistoryRecord toSingle(BtBacktestRecordDO r) {
        List<BackTradeRecord> trades = parseTrades(r.getTradesJson());
        BackTestTradeStats stats = r.getTradeStatsJson() == null ? null
                : JSON.parseObject(r.getTradeStatsJson(), BackTestTradeStats.class);
        if (stats == null) {
            stats = BackTestTradeStats.from(trades, r.getInitCapital(), r.getFinalAsset());
        }
        return SingleBacktestHistoryRecord.builder()
                .id(r.getRecordId())
                .savedAt(r.getSavedAt() == null ? null : r.getSavedAt().format(FMT))
                .stockCode(r.getStockCode())
                .period(r.getPeriod())
                .backStart(r.getBackStart())
                .backEnd(r.getBackEnd())
                .initCapital(r.getInitCapital())
                .finalAsset(r.getFinalAsset())
                .totalRate(r.getTotalRate())
                .maxDrawDown(r.getMaxDrawdown())
                .totalTradeNum(r.getTotalTradeNum())
                .winRate(r.getWinRate())
                .tradeStats(stats)
                .trades(trades)
                .build();
    }

    private PortfolioBacktestHistoryRecord toPortfolio(BtBacktestRecordDO r) {
        List<BackTradeRecord> trades = parseTrades(r.getTradesJson());
        BackTestTradeStats stats = r.getTradeStatsJson() == null ? null
                : JSON.parseObject(r.getTradeStatsJson(), BackTestTradeStats.class);
        if (stats == null) {
            stats = BackTestTradeStats.from(trades, r.getInitCapital(), r.getFinalAsset());
        }
        List<String> codes = r.getStockCodesJson() == null ? null
                : JSON.parseObject(r.getStockCodesJson(), STR_LIST);
        List<SingleStockBackResult> stockResults = r.getStockResultsJson() == null
                ? new ArrayList<SingleStockBackResult>()
                : JSON.parseObject(r.getStockResultsJson(), STOCK_RES_TYPE);
        return PortfolioBacktestHistoryRecord.builder()
                .id(r.getRecordId())
                .savedAt(r.getSavedAt() == null ? null : r.getSavedAt().format(FMT))
                .stockCodeList(codes)
                .backStart(r.getBackStart())
                .backEnd(r.getBackEnd())
                .initCapital(r.getInitCapital())
                .finalAsset(r.getFinalAsset())
                .totalRate(r.getTotalRate())
                .maxDrawDown(r.getMaxDrawdown())
                .totalTradeNum(r.getTotalTradeNum())
                .winRate(r.getWinRate())
                .tradeStats(stats)
                .stockResults(stockResults == null ? new ArrayList<SingleStockBackResult>() : stockResults)
                .trades(trades)
                .build();
    }

    private List<BackTradeRecord> parseTrades(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<BackTradeRecord>();
        }
        List<BackTradeRecord> list = JSON.parseObject(json, TRADE_TYPE);
        return list == null ? new ArrayList<BackTradeRecord>() : list;
    }

    private static String emptyToNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return s.trim();
    }
}
