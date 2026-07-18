package com.quant.stock.backtest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.TypeReference;
import com.quant.stock.backtest.dto.BackTestQueryDTO;
import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.backtest.dto.BackTestTradeStats;
import com.quant.stock.backtest.dto.PortfolioBacktestHistoryRecord;
import com.quant.stock.backtest.dto.PortfolioResultDTO;
import com.quant.stock.backtest.dto.SingleBacktestHistoryRecord;
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
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 回测历史落盘目录由 {@code quant.history-dir} 配置（默认 data/backtest）。
 */
@Slf4j
@Service
public class BackTestHistoryStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<List<SingleBacktestHistoryRecord>> SINGLE_TYPE =
            new TypeReference<List<SingleBacktestHistoryRecord>>() {};
    private static final TypeReference<List<PortfolioBacktestHistoryRecord>> PORTFOLIO_TYPE =
            new TypeReference<List<PortfolioBacktestHistoryRecord>>() {};

    private final QuantProperties props;
    private Path dir;
    private Path singleFile;
    private Path portfolioFile;

    private final ReentrantReadWriteLock singleLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock portfolioLock = new ReentrantReadWriteLock();

    public BackTestHistoryStore(QuantProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        String configured = props.getHistoryDir();
        if (!StringUtils.hasText(configured)) {
            configured = "data/backtest";
        }
        dir = Paths.get(configured.trim());
        singleFile = dir.resolve("single-backtest-records.json");
        portfolioFile = dir.resolve("portfolio-backtest-records.json");
        try {
            Files.createDirectories(dir);
            ensureFile(singleFile);
            ensureFile(portfolioFile);
            log.info("回测历史目录: {}", dir.toAbsolutePath());
        } catch (IOException e) {
            log.error("初始化回测历史目录失败: {}", e.getMessage());
        }
    }

    public SingleBacktestHistoryRecord appendSingle(String period, String backStart, String backEnd,
                                                    BackTestResult result) {
        if (result == null) {
            return null;
        }
        SingleBacktestHistoryRecord rec = SingleBacktestHistoryRecord.fromResult(
                UUID.randomUUID().toString().replace("-", ""),
                LocalDateTime.now().format(FMT),
                period, emptyToNull(backStart), emptyToNull(backEnd), result);
        singleLock.writeLock().lock();
        try {
            List<SingleBacktestHistoryRecord> list = readSingleUnlocked();
            list.add(0, rec);
            writeJson(singleFile, list);
            return rec;
        } finally {
            singleLock.writeLock().unlock();
        }
    }

    public PortfolioBacktestHistoryRecord appendPortfolio(BackTestQueryDTO query, PortfolioResultDTO result) {
        if (result == null) {
            return null;
        }
        PortfolioBacktestHistoryRecord rec = PortfolioBacktestHistoryRecord.fromResult(
                UUID.randomUUID().toString().replace("-", ""),
                LocalDateTime.now().format(FMT),
                query, result);
        portfolioLock.writeLock().lock();
        try {
            List<PortfolioBacktestHistoryRecord> list = readPortfolioUnlocked();
            list.add(0, rec);
            writeJson(portfolioFile, list);
            return rec;
        } finally {
            portfolioLock.writeLock().unlock();
        }
    }

    public List<SingleBacktestHistoryRecord> listSingle(String stockCode) {
        singleLock.readLock().lock();
        try {
            List<SingleBacktestHistoryRecord> all = readSingleUnlocked();
            for (SingleBacktestHistoryRecord r : all) {
                enrichSingleStats(r);
            }
            if (stockCode == null || stockCode.trim().isEmpty()) {
                return all;
            }
            String code = stockCode.trim();
            List<SingleBacktestHistoryRecord> filtered = new ArrayList<SingleBacktestHistoryRecord>();
            for (SingleBacktestHistoryRecord r : all) {
                if (r != null && code.equals(r.getStockCode())) {
                    filtered.add(r);
                }
            }
            return filtered;
        } finally {
            singleLock.readLock().unlock();
        }
    }

    public List<PortfolioBacktestHistoryRecord> listPortfolio() {
        portfolioLock.readLock().lock();
        try {
            List<PortfolioBacktestHistoryRecord> list = readPortfolioUnlocked();
            for (PortfolioBacktestHistoryRecord r : list) {
                enrichPortfolioStats(r);
            }
            return list;
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
            List<SingleBacktestHistoryRecord> list = readSingleUnlocked();
            int before = list.size();
            Iterator<SingleBacktestHistoryRecord> it = list.iterator();
            while (it.hasNext()) {
                SingleBacktestHistoryRecord r = it.next();
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
            List<PortfolioBacktestHistoryRecord> list = readPortfolioUnlocked();
            int n = list.size();
            writeJson(portfolioFile, Collections.emptyList());
            return n;
        } finally {
            portfolioLock.writeLock().unlock();
        }
    }

    private List<SingleBacktestHistoryRecord> readSingleUnlocked() {
        return readList(singleFile, SINGLE_TYPE);
    }

    private List<PortfolioBacktestHistoryRecord> readPortfolioUnlocked() {
        return readList(portfolioFile, PORTFOLIO_TYPE);
    }

    private void enrichSingleStats(SingleBacktestHistoryRecord r) {
        if (r == null || r.getTradeStats() != null) {
            return;
        }
        r.setTradeStats(BackTestTradeStats.from(r.getTrades(), r.getInitCapital(), r.getFinalAsset()));
    }

    private void enrichPortfolioStats(PortfolioBacktestHistoryRecord r) {
        if (r == null || r.getTradeStats() != null) {
            return;
        }
        r.setTradeStats(BackTestTradeStats.from(r.getTrades(), r.getInitCapital(), r.getFinalAsset()));
    }

    private <T> List<T> readList(Path file, TypeReference<List<T>> type) {
        try {
            if (file == null || !Files.exists(file) || Files.size(file) == 0) {
                return new ArrayList<T>();
            }
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (text.isEmpty() || "null".equals(text)) {
                return new ArrayList<T>();
            }
            List<T> list = JSON.parseObject(text, type);
            return list == null ? new ArrayList<T>() : new ArrayList<T>(list);
        } catch (Exception e) {
            log.warn("读取回测历史失败 {}: {}", file, e.getMessage());
            return new ArrayList<T>();
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
            log.error("写入回测历史失败 {}: {}", file, e.getMessage());
            throw new IllegalStateException("写入回测历史失败: " + e.getMessage(), e);
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
