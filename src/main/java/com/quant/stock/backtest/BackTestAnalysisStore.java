package com.quant.stock.backtest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.quant.stock.backtest.dto.AnalysisEvent;
import com.quant.stock.backtest.dto.BackTestAnalysisRecord;
import com.quant.stock.backtest.dto.BackTestQueryDTO;
import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.backtest.dto.BtBacktestAnalysisDO;
import com.quant.stock.backtest.dto.PortfolioResultDTO;
import com.quant.stock.mapper.BacktestAnalysisMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 回测分析：优先写入 MySQL {@code bt_backtest_analysis}。
 */
@Slf4j
@Service
public class BackTestAnalysisStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<List<AnalysisEvent>> EVENT_TYPE =
            new TypeReference<List<AnalysisEvent>>() {};
    private static final TypeReference<List<String>> STR_LIST =
            new TypeReference<List<String>>() {};

    @Autowired(required = false)
    private BacktestAnalysisMapper backtestAnalysisMapper;

    public BackTestAnalysisRecord appendSingle(String id, String savedAt,
                                               String period, String backStart, String backEnd,
                                               BackTestResult result) {
        if (result == null || !StringUtils.hasText(id)) {
            return null;
        }
        List<AnalysisEvent> events = result.getAnalysisEvents() == null
                ? new ArrayList<AnalysisEvent>() : result.getAnalysisEvents();
        BackTestAnalysisRecord rec = BackTestAnalysisRecord.builder()
                .id(id.trim())
                .savedAt(StringUtils.hasText(savedAt) ? savedAt : LocalDateTime.now().format(FMT))
                .kind("SINGLE")
                .stockCode(result.getStockCode())
                .period(period)
                .backStart(emptyToNull(backStart))
                .backEnd(emptyToNull(backEnd))
                .initCapital(result.getInitCapital())
                .finalAsset(result.getFinalAsset())
                .totalTradeNum(result.getTotalTradeNum())
                .eventCount(events.size())
                .summary(result.getAnalysisSummary())
                .events(events)
                .build();
        persist(rec);
        return rec;
    }

    public BackTestAnalysisRecord appendPortfolio(String id, String savedAt,
                                                  BackTestQueryDTO query, PortfolioResultDTO result) {
        if (result == null || !StringUtils.hasText(id)) {
            return null;
        }
        List<AnalysisEvent> events = result.getAnalysisEvents() == null
                ? new ArrayList<AnalysisEvent>() : result.getAnalysisEvents();
        BackTestAnalysisRecord rec = BackTestAnalysisRecord.builder()
                .id(id.trim())
                .savedAt(StringUtils.hasText(savedAt) ? savedAt : LocalDateTime.now().format(FMT))
                .kind("PORTFOLIO")
                .stockCodeList(query == null ? null : query.getStockCodeList())
                .period("DAY")
                .backStart(query == null || query.getBackStart() == null ? null
                        : query.getBackStart().format(FMT))
                .backEnd(query == null || query.getBackEnd() == null ? null
                        : query.getBackEnd().format(FMT))
                .initCapital(result.getInitCapital())
                .finalAsset(result.getFinalAsset())
                .totalTradeNum(result.getTotalTradeNum())
                .eventCount(events.size())
                .summary(result.getAnalysisSummary())
                .events(events)
                .build();
        persist(rec);
        return rec;
    }

    public BackTestAnalysisRecord getSingleById(String id) {
        return getById(id);
    }

    public BackTestAnalysisRecord getPortfolioById(String id) {
        return getById(id);
    }

    public List<BackTestAnalysisRecord> listSingle(String stockCode) {
        if (backtestAnalysisMapper == null) {
            return Collections.emptyList();
        }
        List<BtBacktestAnalysisDO> rows = backtestAnalysisMapper.selectByKind("SINGLE",
                StringUtils.hasText(stockCode) ? stockCode.trim() : null);
        List<BackTestAnalysisRecord> out = new ArrayList<BackTestAnalysisRecord>();
        for (BtBacktestAnalysisDO r : rows) {
            out.add(toRecord(r));
        }
        return out;
    }

    public List<BackTestAnalysisRecord> listPortfolio() {
        if (backtestAnalysisMapper == null) {
            return Collections.emptyList();
        }
        List<BtBacktestAnalysisDO> rows = backtestAnalysisMapper.selectByKind("PORTFOLIO", null);
        List<BackTestAnalysisRecord> out = new ArrayList<BackTestAnalysisRecord>();
        for (BtBacktestAnalysisDO r : rows) {
            out.add(toRecord(r));
        }
        return out;
    }

    public int clearSingleByCode(String stockCode) {
        if (backtestAnalysisMapper == null || !StringUtils.hasText(stockCode)) {
            return 0;
        }
        return backtestAnalysisMapper.deleteSingleByCode(stockCode.trim());
    }

    public int clearAllPortfolio() {
        if (backtestAnalysisMapper == null) {
            return 0;
        }
        return backtestAnalysisMapper.deleteAllByKind("PORTFOLIO");
    }

    private void persist(BackTestAnalysisRecord rec) {
        if (backtestAnalysisMapper == null) {
            log.warn("未启用 MySQL，分析记录未持久化 id={}", rec.getId());
            return;
        }
        BtBacktestAnalysisDO row = BtBacktestAnalysisDO.builder()
                .recordId(rec.getId())
                .kind(rec.getKind())
                .savedAt(LocalDateTime.parse(rec.getSavedAt(), FMT))
                .stockCode(rec.getStockCode())
                .stockCodesJson(rec.getStockCodeList() == null ? null : JSON.toJSONString(rec.getStockCodeList()))
                .period(rec.getPeriod())
                .backStart(rec.getBackStart())
                .backEnd(rec.getBackEnd())
                .initCapital(rec.getInitCapital())
                .finalAsset(rec.getFinalAsset())
                .totalTradeNum(rec.getTotalTradeNum())
                .eventCount(rec.getEventCount())
                .summary(rec.getSummary())
                .eventsJson(JSON.toJSONString(rec.getEvents()))
                .build();
        backtestAnalysisMapper.insert(row);
    }

    private BackTestAnalysisRecord getById(String id) {
        if (backtestAnalysisMapper == null || !StringUtils.hasText(id)) {
            return null;
        }
        BtBacktestAnalysisDO row = backtestAnalysisMapper.selectByRecordId(id.trim());
        return row == null ? null : toRecord(row);
    }

    private BackTestAnalysisRecord toRecord(BtBacktestAnalysisDO r) {
        List<AnalysisEvent> events = r.getEventsJson() == null
                ? new ArrayList<AnalysisEvent>()
                : JSON.parseObject(r.getEventsJson(), EVENT_TYPE);
        List<String> codes = r.getStockCodesJson() == null ? null
                : JSON.parseObject(r.getStockCodesJson(), STR_LIST);
        return BackTestAnalysisRecord.builder()
                .id(r.getRecordId())
                .savedAt(r.getSavedAt() == null ? null : r.getSavedAt().format(FMT))
                .kind(r.getKind())
                .stockCode(r.getStockCode())
                .stockCodeList(codes)
                .period(r.getPeriod())
                .backStart(r.getBackStart())
                .backEnd(r.getBackEnd())
                .initCapital(r.getInitCapital())
                .finalAsset(r.getFinalAsset())
                .totalTradeNum(r.getTotalTradeNum())
                .eventCount(r.getEventCount())
                .summary(r.getSummary())
                .events(events == null ? new ArrayList<AnalysisEvent>() : events)
                .build();
    }

    private static String emptyToNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return s.trim();
    }
}
