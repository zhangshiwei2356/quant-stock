package com.quant.stock.backtest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.TypeReference;
import com.quant.stock.backtest.dto.AnalysisEvent;
import com.quant.stock.backtest.dto.BackTestAnalysisRecord;
import com.quant.stock.backtest.dto.BackTestQueryDTO;
import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.backtest.dto.PortfolioResultDTO;
import com.quant.stock.config.QuantProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 回测分析记录落盘（与成交历史分开）：
 * {@code single-backtest-analysis.json} / {@code portfolio-backtest-analysis.json}
 */
@Slf4j
@Service
public class BackTestAnalysisStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<List<BackTestAnalysisRecord>> TYPE =
            new TypeReference<List<BackTestAnalysisRecord>>() {};

    private final QuantProperties props;
    private Path singleFile;
    private Path portfolioFile;
    private final ReentrantReadWriteLock singleLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock portfolioLock = new ReentrantReadWriteLock();

    public BackTestAnalysisStore(QuantProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        String configured = props.getHistoryDir();
        if (!StringUtils.hasText(configured)) {
            configured = "data/backtest";
        }
        Path dir = Paths.get(configured.trim());
        singleFile = dir.resolve("single-backtest-analysis.json");
        portfolioFile = dir.resolve("portfolio-backtest-analysis.json");
        try {
            Files.createDirectories(dir);
            ensureFile(singleFile);
            ensureFile(portfolioFile);
            log.info("回测分析目录: {}", dir.toAbsolutePath());
        } catch (IOException e) {
            log.error("初始化回测分析目录失败: {}", e.getMessage());
        }
    }

    /**
     * 写入单股分析；{@code id}/{@code savedAt} 须与对应回测历史记录一致（一一对应）。
     */
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
        singleLock.writeLock().lock();
        try {
            List<BackTestAnalysisRecord> list = read(singleFile);
            list.add(0, rec);
            writeJson(singleFile, list);
            return rec;
        } finally {
            singleLock.writeLock().unlock();
        }
    }

    /** 写入组合分析；id/savedAt 与历史记录一致 */
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
        portfolioLock.writeLock().lock();
        try {
            List<BackTestAnalysisRecord> list = read(portfolioFile);
            list.add(0, rec);
            writeJson(portfolioFile, list);
            return rec;
        } finally {
            portfolioLock.writeLock().unlock();
        }
    }

    public BackTestAnalysisRecord getSingleById(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        String key = id.trim();
        singleLock.readLock().lock();
        try {
            for (BackTestAnalysisRecord r : read(singleFile)) {
                if (r != null && key.equals(r.getId())) {
                    return r;
                }
            }
            return null;
        } finally {
            singleLock.readLock().unlock();
        }
    }

    public BackTestAnalysisRecord getPortfolioById(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        String key = id.trim();
        portfolioLock.readLock().lock();
        try {
            for (BackTestAnalysisRecord r : read(portfolioFile)) {
                if (r != null && key.equals(r.getId())) {
                    return r;
                }
            }
            return null;
        } finally {
            portfolioLock.readLock().unlock();
        }
    }

    public List<BackTestAnalysisRecord> listSingle(String stockCode) {
        singleLock.readLock().lock();
        try {
            List<BackTestAnalysisRecord> all = read(singleFile);
            if (stockCode == null || stockCode.trim().isEmpty()) {
                return all;
            }
            String code = stockCode.trim();
            List<BackTestAnalysisRecord> out = new ArrayList<BackTestAnalysisRecord>();
            for (BackTestAnalysisRecord r : all) {
                if (r != null && code.equals(r.getStockCode())) {
                    out.add(r);
                }
            }
            return out;
        } finally {
            singleLock.readLock().unlock();
        }
    }

    public List<BackTestAnalysisRecord> listPortfolio() {
        portfolioLock.readLock().lock();
        try {
            return read(portfolioFile);
        } finally {
            portfolioLock.readLock().unlock();
        }
    }

    public int clearSingleByCode(String stockCode) {
        if (stockCode == null || stockCode.trim().isEmpty()) {
            return 0;
        }
        String code = stockCode.trim();
        singleLock.writeLock().lock();
        try {
            List<BackTestAnalysisRecord> list = read(singleFile);
            int before = list.size();
            Iterator<BackTestAnalysisRecord> it = list.iterator();
            while (it.hasNext()) {
                BackTestAnalysisRecord r = it.next();
                if (r != null && code.equals(r.getStockCode())) {
                    it.remove();
                }
            }
            writeJson(singleFile, list);
            return before - list.size();
        } finally {
            singleLock.writeLock().unlock();
        }
    }

    public int clearAllPortfolio() {
        portfolioLock.writeLock().lock();
        try {
            int n = read(portfolioFile).size();
            writeJson(portfolioFile, Collections.emptyList());
            return n;
        } finally {
            portfolioLock.writeLock().unlock();
        }
    }

    private List<BackTestAnalysisRecord> read(Path file) {
        try {
            if (file == null || !Files.exists(file) || Files.size(file) == 0) {
                return new ArrayList<BackTestAnalysisRecord>();
            }
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (text.isEmpty() || "null".equals(text)) {
                return new ArrayList<BackTestAnalysisRecord>();
            }
            List<BackTestAnalysisRecord> list = JSON.parseObject(text, TYPE);
            return list == null ? new ArrayList<BackTestAnalysisRecord>() : new ArrayList<BackTestAnalysisRecord>(list);
        } catch (Exception e) {
            log.warn("读取分析记录失败 {}: {}", file, e.getMessage());
            return new ArrayList<BackTestAnalysisRecord>();
        }
    }

    private void writeJson(Path file, Object data) {
        try {
            Files.createDirectories(file.getParent());
            String json = JSON.toJSONString(data,
                    JSONWriter.Feature.PrettyFormat,
                    JSONWriter.Feature.WriteMapNullValue);
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("写入分析记录失败 {}: {}", file, e.getMessage());
            throw new IllegalStateException("写入分析记录失败: " + e.getMessage(), e);
        }
    }

    private void ensureFile(Path file) throws IOException {
        if (!Files.exists(file)) {
            Files.write(file, "[]".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String emptyToNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return s.trim();
    }
}
