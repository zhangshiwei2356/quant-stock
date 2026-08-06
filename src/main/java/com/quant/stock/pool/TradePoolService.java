package com.quant.stock.pool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.quant.stock.backtest.BatchStockBackTestService;
import com.quant.stock.backtest.dto.BatchScanResultDTO;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.mapper.FactorDailyMapper;
import com.quant.stock.mapper.StockBasicMapper;
import com.quant.stock.mapper.TradePoolMapper;
import com.quant.stock.mapper.TradePoolReportMapper;
import com.quant.stock.market.FactorDailyComputeService;
import com.quant.stock.market.TdxScriptBackfillService;
import com.quant.stock.market.dto.FactorDailyDO;
import com.quant.stock.market.dto.StockBasicDO;
import com.quant.stock.pdf.HtmlPdfExporter;
import com.quant.stock.pool.dto.TradePoolDO;
import com.quant.stock.pool.dto.TradePoolReportDO;
import com.quant.stock.task.JobProgressHub;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 唯一交易目标池：盘后全市场扫描按分数 TopN 自动覆盖 {@code trade_pool}；
 * 实盘分钟扫描只打活跃池。移出目标池不等于卖出持仓。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class TradePoolService {

    private final TradePoolMapper tradePoolMapper;
    private final TradePoolReportMapper reportMapper;
    private final StockBasicMapper stockBasicMapper;
    private final FactorDailyMapper factorDailyMapper;
    private final FactorDailyComputeService factorDailyComputeService;
    private final TdxScriptBackfillService tdxScriptBackfillService;
    private final BatchStockBackTestService batchStockBackTestService;
    private final PoolSelectScorer poolSelectScorer;
    private final QuantProperties quantProperties;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final JobProgressHub jobProgressHub;

    /** 最近一次入池扫描漏斗（内存；供页面刷新后回填「扫描漏斗」） */
    private final AtomicReference<Map<String, Object>> lastScanStatsRef =
            new AtomicReference<Map<String, Object>>();

    /** 启动时确保目标池相关表结构存在并清理废弃表名 */
    @PostConstruct
    public void initSchemaAndSeed() {
        ensureTables();
    }

    /** 目标池活跃代码（实盘扫描用） */
    public List<String> listActiveCodes() {
        List<String> codes = new ArrayList<String>();
        for (TradePoolDO row : tradePoolMapper.selectActive()) {
            if (row.getSymbol() != null && !row.getSymbol().isEmpty()) {
                codes.add(row.getSymbol());
            }
        }
        return codes;
    }

    /**
     * 单只目标池分析报告：优先读活跃行 {@code report_id}，否则最新报告，再否则即时重算。
     */
    public Map<String, Object> analysis(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) {
            throw new IllegalArgumentException("股票代码不能为空");
        }
        String code = symbol.trim();
        TradePoolDO row = tradePoolMapper.selectBySymbol(code);
        if (row != null && row.getStatus() != null && row.getStatus() == 1 && row.getReportId() != null) {
            Map<String, Object> fromDb = reportViewById(row.getReportId());
            if (fromDb != null) {
                fromDb.put("status", row.getStatus());
                fromDb.put("poolReason", row.getReason());
                fromDb.put("source", row.getSource());
                fromDb.put("enteredAt", row.getEnteredAt() == null ? null : row.getEnteredAt().toString());
                return fromDb;
            }
        }
        TradePoolReportDO latest = reportMapper.selectLatestBySymbol(code);
        if (latest != null) {
            Map<String, Object> fromDb = reportViewById(latest.getId());
            if (fromDb != null) {
                if (row != null) {
                    fromDb.put("status", row.getStatus());
                    fromDb.put("poolReason", row.getReason());
                    fromDb.put("source", row.getSource());
                }
                return fromDb;
            }
        }
        return livePoolAnalysis(code, row);
    }

    /** 按报告 id 读取 */
    public Map<String, Object> reportById(Long reportId) {
        if (reportId == null) {
            throw new IllegalArgumentException("报告 id 不能为空");
        }
        Map<String, Object> m = reportViewById(reportId);
        if (m == null) {
            throw new IllegalArgumentException("报告不存在: " + reportId);
        }
        return m;
    }

    /** 扫描批次列表（按时间倒序） */
    public Map<String, Object> listScanBatches(int limit) {
        int lim = Math.max(1, Math.min(limit <= 0 ? 30 : limit, 100));
        List<Map<String, Object>> raw = reportMapper.selectBatchSummaries(lim);
        List<Map<String, Object>> batches = new ArrayList<Map<String, Object>>();
        if (raw != null) {
            for (Map<String, Object> r : raw) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("batchId", r.get("batchId"));
                m.put("reportCount", r.get("reportCount"));
                m.put("createdAt", r.get("createdAt") == null ? null : r.get("createdAt").toString());
                m.put("maxScore", r.get("maxScore"));
                m.put("avgScore", r.get("avgScore"));
                batches.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("count", batches.size());
        out.put("items", batches);
        out.put("hint", "每次「扫描更新」或 pool-rebuild 生成一批 trade_pool_report。");
        return out;
    }

    /** 某一扫描批次入选明细 */
    public Map<String, Object> listScanBatchDetail(String batchId) {
        if (batchId == null || batchId.trim().isEmpty()) {
            throw new IllegalArgumentException("batchId 不能为空");
        }
        List<TradePoolReportDO> rows = reportMapper.selectByBatchId(batchId.trim());
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (rows != null) {
            for (TradePoolReportDO r : rows) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("reportId", r.getId());
                m.put("code", r.getSymbol());
                m.put("name", r.getName());
                m.put("score", r.getScore());
                m.put("reason", r.getReason());
                m.put("summary", r.getSummary());
                m.put("batchId", r.getBatchId());
                m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
                items.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("batchId", batchId.trim());
        out.put("count", items.size());
        out.put("items", items);
        return out;
    }

    private Map<String, Object> reportViewById(Long reportId) {
        TradePoolReportDO report = reportMapper.selectById(reportId);
        if (report == null || report.getAnalysisJson() == null || report.getAnalysisJson().isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> m = JSON.parseObject(report.getAnalysisJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            if (m == null) {
                m = new LinkedHashMap<String, Object>();
            }
            m.put("reportId", report.getId());
            m.put("fromDb", true);
            if (report.getCreatedAt() != null && m.get("reportCreatedAt") == null) {
                m.put("reportCreatedAt", report.getCreatedAt().toString());
            }
            if (report.getSummary() != null && !report.getSummary().isEmpty()) {
                m.put("summary", report.getSummary());
            }
            return m;
        } catch (Exception e) {
            log.warn("解析目标池报告 JSON 失败 id={}: {}", reportId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> livePoolAnalysis(String code, TradePoolDO row) {
        BatchScanResultDTO r = batchStockBackTestService.analyzeOne(code);
        if (r == null) {
            throw new IllegalArgumentException("无法分析该股票（K线不足或扫描失败）: " + code);
        }
        String name = row != null && row.getName() != null ? row.getName() : code;
        poolSelectScorer.applyScores(Collections.singletonList(r));
        boolean can = poolSelectScorer.isEligible(r);
        Map<String, Object> m = buildAnalysisMap(code, name, r, can);
        if (row != null) {
            m.put("status", row.getStatus());
            m.put("poolReason", row.getReason());
            m.put("source", row.getSource());
            m.put("enteredAt", row.getEnteredAt() == null ? null : row.getEnteredAt().toString());
        }
        m.put("summary", buildRecommendSummary(name, code, r, can));
        m.put("fromDb", false);
        return m;
    }

    private Map<String, Object> buildAnalysisMap(String code, String name, BatchScanResultDTO r, boolean can) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", code);
        m.put("name", name == null ? code : name);
        m.put("score", r.getPoolScore() != null ? r.getPoolScore() : r.getTotalRate());
        m.put("scoreLabel", r.getPoolScore() == null ? "—" : (r.getPoolScore().toPlainString() + "分"));
        m.put("scorePct", r.getPoolScore() == null ? pct(r.getTotalRate()) : (r.getPoolScore().toPlainString() + "分"));
        m.put("backtestRate", r.getTotalRate());
        m.put("backtestRatePct", pct(r.getTotalRate()));
        m.put("maxDrawDown", r.getMaxDrawDown());
        m.put("maxDrawDownPct", pct(r.getMaxDrawDown()));
        m.put("winRate", r.getWinRate());
        m.put("winRatePct", pct(r.getWinRate()));
        m.put("trades", r.getTotalTradeNum());
        m.put("lastClose", r.getLastClose());
        m.put("ma5", r.getMa5());
        m.put("ma10", r.getMa10());
        m.put("ma20", r.getMa20());
        m.put("ma60", r.getMa60());
        m.put("rsi14", r.getRsi14());
        m.put("atr14", r.getAtr14());
        m.put("adx14", r.getAdx14());
        m.put("mom5", r.getMom5());
        m.put("mom20", r.getMom20());
        m.put("signal", r.getSignalDesc());
        m.put("canBuyNow", r.getCanBuyNow());
        m.put("canRecommend", can);
        m.put("recommendReason", poolReason(r));
        m.put("scoreTag", r.getScoreTag());
        m.put("decision", can ? ("可入目标池 · " + poolReason(r)) : "暂不入选");
        return m;
    }

    private String buildRecommendSummary(String name, String code, BatchScanResultDTO r, boolean can) {
        StringBuilder sb = new StringBuilder();
        sb.append(name == null ? code : name)
                .append('(').append(code).append(')')
                .append(can ? " 建议纳入目标池。" : " 当前不建议纳入。")
                .append(" 综合分 ").append(r.getPoolScore() == null ? "—" : r.getPoolScore().toPlainString())
                .append("，回测收益 ").append(pct(r.getTotalRate()))
                .append("，最大回撤 ").append(pct(r.getMaxDrawDown()))
                .append("，信号：").append(r.getSignalDesc() == null ? "—" : r.getSignalDesc())
                .append("。入池依据：").append(poolReason(r))
                .append('。');
        return sb.toString();
    }

    /** 当前活跃目标池概览（含上限配置） */
    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (TradePoolDO row : tradePoolMapper.selectActive()) {
            items.add(toPoolView(row));
        }
        m.put("items", items);
        m.put("count", items.size());
        m.put("maxFinal", quantProperties.getTradePoolMax());
        Map<String, Object> lastScan = lastScanStatsRef.get();
        if (lastScan != null) {
            m.put("lastScan", lastScan);
        }
        return m;
    }

    /**
     * 全市场列表：优先 stock_basic；空则回退配置 stock-codes。
     */
    public List<Map<String, String>> listUniverse() {
        List<StockBasicDO> basics = stockBasicMapper.selectAll();
        List<Map<String, String>> list = new ArrayList<Map<String, String>>();
        if (basics != null && !basics.isEmpty()) {
            for (StockBasicDO b : basics) {
                if (b.getStatus() != null && b.getStatus() == 0) {
                    continue;
                }
                Map<String, String> m = new LinkedHashMap<String, String>();
                m.put("code", b.getSymbol());
                m.put("name", b.getName() == null ? b.getSymbol() : b.getName());
                if (b.getIsSt() != null) {
                    m.put("isSt", String.valueOf(b.getIsSt()));
                }
                if (b.getListDate() != null) {
                    m.put("listDate", b.getListDate().toString());
                }
                list.add(m);
            }
            return list;
        }
        for (String code : quantProperties.stockCodeList()) {
            Map<String, String> m = new LinkedHashMap<String, String>();
            m.put("code", code);
            m.put("name", code);
            list.add(m);
        }
        return list;
    }

    private List<String> coarseFilter(List<Map<String, String>> universe) {
        List<String> out = new ArrayList<String>();
        int minListDays = Math.max(0, quantProperties.getPoolMinListDays());
        LocalDate today = LocalDate.now();
        for (Map<String, String> u : universe) {
            String code = u.get("code");
            if (code == null) {
                continue;
            }
            String st = u.get("isSt");
            if ("1".equals(st) || "true".equalsIgnoreCase(st)) {
                continue;
            }
            if (minListDays > 0 && u.get("listDate") != null) {
                try {
                    LocalDate ld = LocalDate.parse(u.get("listDate"));
                    if (ChronoUnit.DAYS.between(ld, today) < minListDays) {
                        continue;
                    }
                } catch (Exception ignored) {
                    // 日期异常不拦截
                }
            }
            out.add(code);
        }
        return filterByFactorDaily(out);
    }

    /**
     * 有 factor_daily 时做廉价趋势门：保留 ma5>ma20 或 ma60 向上或放量突破；
     * 无因子行则放行（兼容未导入因子的标的）。
     */
    private List<String> filterByFactorDaily(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return codes;
        }
        Map<String, FactorDailyDO> latest = new HashMap<String, FactorDailyDO>();
        try {
            // 分批避免 IN 过长
            final int batch = 500;
            for (int i = 0; i < codes.size(); i += batch) {
                int to = Math.min(i + batch, codes.size());
                List<FactorDailyDO> rows = factorDailyMapper.selectLatestBySymbols(codes.subList(i, to));
                if (rows == null) {
                    continue;
                }
                for (FactorDailyDO f : rows) {
                    if (f != null && f.getSymbol() != null) {
                        latest.put(f.getSymbol(), f);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取 factor_daily 失败，跳过因子粗筛: {}", e.getMessage());
            return codes;
        }
        if (latest.isEmpty()) {
            return codes;
        }
        List<String> kept = new ArrayList<String>();
        int dropped = 0;
        for (String code : codes) {
            FactorDailyDO f = latest.get(code);
            if (f == null) {
                kept.add(code);
                continue;
            }
            if (passFactorGate(f)) {
                kept.add(code);
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            log.info("factor_daily 粗筛剔除 {} 只，剩余 {}", dropped, kept.size());
        }
        return kept;
    }

    private static boolean passFactorGate(FactorDailyDO f) {
        if (f.getMa5() != null && f.getMa20() != null && f.getMa5().compareTo(f.getMa20()) > 0) {
            return true;
        }
        if (f.getMa60Up() != null && f.getMa60Up() == 1) {
            return true;
        }
        return f.getIsVolumeBreak() != null && f.getIsVolumeBreak() == 1;
    }

    /** 扫描后流动性粗筛（依赖 K 线算得的均成交额近似） */
    private void filterByAvgAmount(List<BatchScanResultDTO> scanned) {
        long minAmt = quantProperties.getPoolMinAvgAmount20();
        if (minAmt <= 0 || scanned == null || scanned.isEmpty()) {
            return;
        }
        Iterator<BatchScanResultDTO> it = scanned.iterator();
        while (it.hasNext()) {
            BatchScanResultDTO r = it.next();
            if (r.getAvgAmount20() != null && r.getAvgAmount20().longValue() < minAmt) {
                it.remove();
            }
        }
    }

    /**
     * 全市场扫描分析报告：打分、覆盖唯一目标池，并落盘 PDF。
     * <p>
     * 注意：扫描本身可能数十分钟，不可包在长事务里；池替换由 {@link #replaceActivePool} 自管短事务。
     */
    public Map<String, Object> analyzeAndRecommend() {
        Map<String, Object> rebuild = rebuildFromUniverse();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        @SuppressWarnings("unchecked")
        List<String> selectedCodes = rebuild.get("codes") instanceof List
                ? (List<String>) rebuild.get("codes")
                : Collections.<String>emptyList();
        for (TradePoolDO row : tradePoolMapper.selectActive()) {
            Map<String, Object> item = toPoolView(row);
            item.put("decision", "已入目标池");
            item.put("canRecommend", true);
            item.put("scorePct", row.getScore() == null ? "—" : (row.getScore().toPlainString() + "分"));
            rows.add(item);
        }
        String reportPath = writePdfReport(rows, selectedCodes, rebuild);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.putAll(rebuild);
        out.put("analysis", rows);
        out.put("eligibleCodes", selectedCodes);
        out.put("reportPath", reportPath);
        if (reportPath != null) {
            try {
                out.put("reportFileName", Paths.get(reportPath).getFileName().toString());
            } catch (Exception ignored) {
                // leave unset
            }
        }
        rememberLastScan(out);
        return out;
    }

    /** 缓存漏斗字段，供目标池页刷新后回填。 */
    private void rememberLastScan(Map<String, Object> src) {
        if (src == null || src.isEmpty()) {
            return;
        }
        Map<String, Object> slim = new LinkedHashMap<String, Object>();
        copyIfPresent(src, slim, "universe");
        copyIfPresent(src, slim, "afterCoarse");
        copyIfPresent(src, slim, "afterScan");
        copyIfPresent(src, slim, "afterLiquidity");
        copyIfPresent(src, slim, "scanned");
        copyIfPresent(src, slim, "selected");
        copyIfPresent(src, slim, "batchId");
        copyIfPresent(src, slim, "scoreMin");
        copyIfPresent(src, slim, "tradePoolMax");
        copyIfPresent(src, slim, "reportFileName");
        copyIfPresent(src, slim, "reportPath");
        lastScanStatsRef.set(slim);
    }

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst, String key) {
        if (src.containsKey(key)) {
            dst.put(key, src.get(key));
        }
    }

    /**
     * 安全读取 historyDir/reports 下 pool-*.pdf（及历史 .md）报告内容（供下载）。
     */
    public byte[] readReportFile(String fileName) {
        if (fileName == null || !fileName.matches("pool-\\d{8}-\\d{6}\\.(pdf|md)")) {
            throw new IllegalArgumentException("非法报告文件名");
        }
        Path file = Paths.get(quantProperties.getHistoryDir(), "reports", fileName).normalize();
        Path dir = Paths.get(quantProperties.getHistoryDir(), "reports").normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("报告不存在: " + fileName);
        }
        try {
            return Files.readAllBytes(file);
        } catch (Exception e) {
            throw new IllegalArgumentException("读取报告失败: " + e.getMessage());
        }
    }

    /**
     * 全市场扫描 → 粗过滤 → TopN 可入选 → 整表替换唯一目标池。
     * 可选先重算 factor_daily（独立提交，不包进池替换事务）。
     */
    public Map<String, Object> rebuildFromUniverse() {
        Map<String, Object> factorRefresh = null;
        if (quantProperties.isPoolRebuildRefreshFactors()) {
            try {
                jobProgressHub.phase("running", "执行中", "入池前：重算日频因子…");
                factorRefresh = factorDailyComputeService.rebuild(null,
                        new FactorDailyComputeService.ProgressCallback() {
                            @Override
                            public void onProgress(int done, int total, String symbol) {
                                jobProgressHub.tick(done, total, symbol,
                                        "因子预刷新 " + done + "/" + total
                                                + (symbol != null ? (" · " + symbol) : ""));
                            }
                        });
                log.info("[pool-rebuild] factor_daily 预刷新 {}", factorRefresh);
            } catch (Exception e) {
                log.warn("[pool-rebuild] factor_daily 预刷新失败，继续扫池: {}", e.getMessage());
            }
        }
        jobProgressHub.phase("running", "执行中", "入池：粗筛与扫描…");
        List<Map<String, String>> uni = listUniverse();
        Map<String, String> nameByCode = new HashMap<String, String>();
        for (Map<String, String> u : uni) {
            nameByCode.put(u.get("code"), u.get("name"));
        }
        List<String> codes = coarseFilter(uni);
        int afterCoarse = codes.size();
        // 重置进度：因子预刷可能已到 100%，否则页面会一直显示「5200/5200 · 开始扫描」像卡住
        jobProgressHub.tick(0, Math.max(1, afterCoarse), null,
                "粗筛后 " + afterCoarse + " 只，开始扫描…"
                        + (quantProperties.isPoolRebuildFullBacktest() ? "（完整回测）" : "（轻量·无全量回测）"));
        final boolean light = !quantProperties.isPoolRebuildFullBacktest();
        List<BatchScanResultDTO> scanned = codes.isEmpty()
                ? new ArrayList<BatchScanResultDTO>()
                : new ArrayList<BatchScanResultDTO>(batchStockBackTestService.scan(codes,
                new BatchStockBackTestService.ProgressCallback() {
                    @Override
                    public void onProgress(int done, int total, String symbol) {
                        jobProgressHub.tick(done, total, symbol,
                                "入池扫描 " + done + "/" + total
                                        + (light ? " ·轻量" : "")
                                        + (symbol != null ? (" · " + symbol) : ""));
                    }
                }, light));
        int afterScan = scanned.size();
        filterByAvgAmount(scanned);
        int afterLiquidity = scanned.size();
        int max = Math.max(1, quantProperties.getTradePoolMax());
        jobProgressHub.note("扫描完成 " + afterLiquidity + " 只，选取 Top" + max + "…");
        List<BatchScanResultDTO> picked = poolSelectScorer.pickTop(scanned, max);
        String batchId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> selected = replaceActivePool(picked, nameByCode, batchId);
        log.info("[pool-rebuild] universe={} afterCoarse={} afterScan={} afterLiquidity={} selected={} batchId={} scoreMin={}",
                uni.size(), afterCoarse, afterScan, afterLiquidity, selected.size(), batchId,
                quantProperties.getPoolScoreMin());
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("universe", uni.size());
        out.put("afterCoarse", afterCoarse);
        out.put("afterScan", afterScan);
        out.put("afterLiquidity", afterLiquidity);
        out.put("scanned", afterLiquidity);
        out.put("selected", selected.size());
        out.put("codes", selected);
        out.put("batchId", batchId);
        out.put("scoreMin", quantProperties.getPoolScoreMin());
        out.put("tradePoolMax", max);
        out.put("poolRebuildRefreshFactors", quantProperties.isPoolRebuildRefreshFactors());
        out.put("poolRebuildFullBacktest", quantProperties.isPoolRebuildFullBacktest());
        out.put("scanMode", light ? "light" : "fullBacktest");
        if (factorRefresh != null) {
            out.put("factorRefresh", factorRefresh);
        }
        out.put("minuteBackfillHint", "python scripts/fetch_min1_tdx.py --from-pool");
        out.put("minuteBackfillCodes", selected);
        if (quantProperties.isPoolRebuildBackfillMinute()) {
            try {
                jobProgressHub.note("提交池内分钟异步回填…");
                Map<String, Object> bf = tdxScriptBackfillService.backfillPoolMinuteAsync();
                out.put("minuteBackfill", bf);
            } catch (Exception e) {
                log.warn("[pool-rebuild] 池内分钟异步回填提交失败: {}", e.getMessage());
                Map<String, Object> bf = new LinkedHashMap<String, Object>();
                bf.put("ok", false);
                bf.put("message", e.getMessage());
                out.put("minuteBackfill", bf);
            }
        }
        jobProgressHub.note("入池完成 selected=" + selected.size() + " batchId=" + batchId);
        rememberLastScan(out);
        return out;
    }

    private List<String> replaceActivePool(final List<BatchScanResultDTO> picked,
                                          final Map<String, String> nameByCode,
                                          final String batchId) {
        return new TransactionTemplate(transactionManager).execute(
                new org.springframework.transaction.support.TransactionCallback<List<String>>() {
                    @Override
                    public List<String> doInTransaction(
                            org.springframework.transaction.TransactionStatus status) {
                        tradePoolMapper.deactivateAll();
                        List<String> selected = new ArrayList<String>();
                        for (BatchScanResultDTO r : picked) {
                            String code = r.getStockCode();
                            String name = nameByCode.getOrDefault(code, code);
                            Map<String, Object> analysis = buildAnalysisMap(code, name, r, true);
                            String summary = buildRecommendSummary(name, code, r, true);
                            analysis.put("summary", summary);
                            analysis.put("batchId", batchId);
                            BigDecimal poolScore = r.getPoolScore() == null ? BigDecimal.ZERO : r.getPoolScore();
                            TradePoolReportDO report = TradePoolReportDO.builder()
                                    .symbol(code).name(name)
                                    .score(poolScore)
                                    .reason(trimReason(poolReason(r) + " | " + r.getSignalDesc()))
                                    .summary(summary.length() > 1000 ? summary.substring(0, 1000) : summary)
                                    .analysisJson(JSON.toJSONString(analysis))
                                    .batchId(batchId)
                                    .build();
                            reportMapper.insert(report);
                            tradePoolMapper.upsert(TradePoolDO.builder()
                                    .symbol(code).name(name).status(1)
                                    .score(report.getScore()).reason(report.getReason())
                                    .source("BATCH_SCAN").reportId(report.getId())
                                    .build());
                            selected.add(code);
                        }
                        return selected;
                    }
                });
    }

    /**
     * 从目标池手动移出：仅停用池内记录，不卖出持仓、不写历史。
     */
    @Transactional
    public Map<String, Object> removeFromPool(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) {
            throw new IllegalArgumentException("股票代码不能为空");
        }
        String code = symbol.trim();
        TradePoolDO row = tradePoolMapper.selectBySymbol(code);
        if (row == null || row.getStatus() == null || row.getStatus() != 1) {
            throw new IllegalArgumentException("目标池中无活跃标的: " + code);
        }
        tradePoolMapper.deactivateBySymbol(code);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("removed", code);
        out.put("count", tradePoolMapper.countActive());
        return out;
    }

    private void ensureTables() {
        jdbcTemplate.update("DROP TABLE IF EXISTS `trade_pool_history`");
        jdbcTemplate.update("DROP TABLE IF EXISTS `trade_pool_recommend`");
        jdbcTemplate.update("DROP TABLE IF EXISTS `trade_pool_recommend_report`");
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS `trade_pool_report` ("
                        + "`id` BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "`symbol` VARCHAR(10) NOT NULL COMMENT '股票代码',"
                        + "`name` VARCHAR(32) DEFAULT NULL,"
                        + "`score` DECIMAL(12,6) DEFAULT NULL,"
                        + "`reason` VARCHAR(256) DEFAULT NULL COMMENT '入选依据摘要',"
                        + "`summary` VARCHAR(1024) DEFAULT NULL,"
                        + "`analysis_json` LONGTEXT COMMENT '完整分析 JSON',"
                        + "`batch_id` VARCHAR(32) DEFAULT NULL COMMENT '同一次扫描批次',"
                        + "`created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,"
                        + "KEY `idx_symbol_time` (`symbol`, `created_at`),"
                        + "KEY `idx_batch` (`batch_id`)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='目标池入选分析报告'"
        );
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS `trade_pool` ("
                        + "`id` BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "`symbol` VARCHAR(10) NOT NULL COMMENT '股票代码',"
                        + "`name` VARCHAR(32) DEFAULT NULL COMMENT '简称',"
                        + "`status` TINYINT NOT NULL DEFAULT 1 COMMENT '1在池 0停用',"
                        + "`score` DECIMAL(12,6) DEFAULT NULL COMMENT '扫描分数',"
                        + "`reason` VARCHAR(256) DEFAULT NULL COMMENT '入选原因',"
                        + "`source` VARCHAR(16) NOT NULL DEFAULT 'BATCH_SCAN' COMMENT 'BATCH_SCAN/MANUAL',"
                        + "`report_id` BIGINT DEFAULT NULL COMMENT '关联 trade_pool_report.id',"
                        + "`entered_at` DATETIME DEFAULT CURRENT_TIMESTAMP,"
                        + "`updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                        + "UNIQUE KEY `uk_symbol` (`symbol`),"
                        + "KEY `idx_status` (`status`),"
                        + "KEY `idx_report` (`report_id`)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='量化交易目标池（盘后扫描自动覆盖；盘中开仓名单）'"
        );
        ensureColumn("trade_pool", "report_id",
                "ALTER TABLE `trade_pool` ADD COLUMN `report_id` BIGINT DEFAULT NULL COMMENT '关联 trade_pool_report.id' AFTER `source`");
        ensureColumn("trade_pool", "source",
                "ALTER TABLE `trade_pool` ADD COLUMN `source` VARCHAR(16) NOT NULL DEFAULT 'BATCH_SCAN' COMMENT 'BATCH_SCAN/MANUAL' AFTER `reason`");
    }

    private void ensureColumn(String table, String column, String alterSql) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (n == null || n == 0) {
                jdbcTemplate.execute(alterSql);
                log.info("已补齐列 {}.{}", table, column);
            }
        } catch (Exception e) {
            log.warn("检查/补齐列 {}.{} 失败: {}", table, column, e.getMessage());
        }
    }

    private String poolReason(BatchScanResultDTO r) {
        if (r.getScoreTag() != null && !r.getScoreTag().isEmpty()) {
            return r.getScoreTag();
        }
        if (Boolean.TRUE.equals(r.getCanBuyNow())) {
            return "金叉可买";
        }
        if (r.getMa5() != null && r.getMa20() != null && r.getMa5().compareTo(r.getMa20()) > 0) {
            return "MA5>MA20多头";
        }
        return "不符合";
    }

    private static String trimReason(String s) {
        if (s == null) {
            return "可买入";
        }
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    private static String pct(BigDecimal v) {
        if (v == null) {
            return "—";
        }
        return v.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /**
     * 将扫描结果落盘为 PDF（historyDir/reports/pool-yyyyMMdd-HHmmss.pdf）。
     */
    private String writePdfReport(List<Map<String, Object>> rows, List<String> eligible,
                                  Map<String, Object> rebuild) {
        try {
            Path dir = Paths.get(quantProperties.getHistoryDir(), "reports");
            Files.createDirectories(dir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path file = dir.resolve("pool-" + ts + ".pdf");
            byte[] pdf = HtmlPdfExporter.toPdfBytes(buildPoolReportXhtml(rows, eligible, rebuild));
            Files.write(file, pdf);
            log.info("目标池分析报告已写入 {}", file.toAbsolutePath());
            return file.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("写目标池报告失败: {}", e.getMessage());
            return null;
        }
    }

    /** 构建目标池扫描报告 XHTML（供 iText XMLWorker 转 PDF）。 */
    static String buildPoolReportXhtml(List<Map<String, Object>> rows, List<String> eligible,
                                       Map<String, Object> rebuild) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>量化目标池扫描分析报告</h1>");
        body.append("<ul>");
        body.append("<li>生成时间：").append(esc(String.valueOf(LocalDateTime.now()))).append("</li>");
        body.append("<li>全市场：").append(esc(String.valueOf(rebuild.get("universe")))).append(" 只</li>");
        body.append("<li>粗筛后：").append(esc(String.valueOf(rebuild.get("afterCoarse")))).append(" 只</li>");
        body.append("<li>扫描返回：").append(esc(String.valueOf(rebuild.get("afterScan")))).append(" 只</li>");
        body.append("<li>流动性后：").append(esc(String.valueOf(rebuild.get("afterLiquidity")))).append(" 只</li>");
        body.append("<li>写入目标池：").append(esc(String.valueOf(rebuild.get("selected")))).append(" 只</li>");
        body.append("<li>分数下限：").append(esc(String.valueOf(rebuild.get("scoreMin")))).append("</li>");
        body.append("<li>批次：").append(esc(String.valueOf(rebuild.get("batchId")))).append("</li>");
        body.append("<li>入选代码：")
                .append(esc(eligible == null || eligible.isEmpty() ? "（无）" : String.join(", ", eligible)))
                .append("</li>");
        body.append("</ul>");
        body.append("<table><thead><tr>")
                .append("<th>代码</th><th>名称</th><th>分数</th><th>原因</th><th>来源</th><th>报告ID</th>")
                .append("</tr></thead><tbody>");
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                Object score = r.get("scorePct") != null ? r.get("scorePct") : r.get("score");
                body.append("<tr>")
                        .append("<td>").append(esc(String.valueOf(r.get("code")))).append("</td>")
                        .append("<td>").append(esc(String.valueOf(r.get("name")))).append("</td>")
                        .append("<td>").append(esc(String.valueOf(score))).append("</td>")
                        .append("<td>").append(esc(String.valueOf(r.get("reason")))).append("</td>")
                        .append("<td>").append(esc(String.valueOf(r.get("source")))).append("</td>")
                        .append("<td>").append(esc(String.valueOf(r.get("reportId")))).append("</td>")
                        .append("</tr>");
            }
        }
        body.append("</tbody></table>");
        body.append("<h2>说明</h2><ul>");
        body.append("<li><b>分数</b>为多因子综合分（0~100）：趋势均线/MA60斜率/ADX + 动量排名 + 波动 + 流动性。</li>");
        body.append("<li><b>可入目标池</b>：综合分≥配置下限，且金叉可买或 MA5&gt;MA20（RSI 未过热）。</li>");
        body.append("<li>回测收益率仅作参考，不再作为主排序。</li>");
        body.append("<li>盘后扫描会 deactivateAll 后按 TopN 覆盖唯一目标池（source=BATCH_SCAN）。</li>");
        body.append("<li>手动移出目标池仅停用池记录，不卖出持仓。</li>");
        body.append("<li>入选时写入表 trade_pool_report，由 trade_pool.report_id 关联。</li>");
        body.append("</ul>");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" "
                + "\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"/>"
                + "<title>目标池扫描报告</title>"
                + "<style type=\"text/css\">"
                + "body{font-size:11pt;line-height:1.55;color:#222;}"
                + "h1{font-size:18pt;margin:0 0 10px 0;}"
                + "h2{font-size:14pt;margin:18px 0 8px 0;}"
                + "ul{margin:6px 0 6px 22px;}"
                + "li{font-size:11pt;}"
                + "table{border-collapse:collapse;width:100%;margin:8px 0;}"
                + "th,td{border:1px solid #aaa;padding:4px 6px;font-size:10pt;}"
                + "</style></head><body>"
                + body
                + "</body></html>";
    }

    private static String esc(String s) {
        if (s == null || "null".equals(s)) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static Map<String, Object> toPoolView(TradePoolDO row) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", row.getSymbol());
        m.put("name", row.getName() == null ? row.getSymbol() : row.getName());
        m.put("status", row.getStatus());
        m.put("score", row.getScore());
        m.put("reason", row.getReason());
        m.put("source", row.getSource());
        m.put("reportId", row.getReportId());
        m.put("enteredAt", row.getEnteredAt() == null ? null : row.getEnteredAt().toString());
        m.put("updatedAt", row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
        return m;
    }
}
