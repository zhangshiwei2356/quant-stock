package com.quant.stock.strategy;

import com.quant.stock.backtest.BackTestAnalysisStore;
import com.quant.stock.backtest.BackTestHistoryStore;
import com.quant.stock.backtest.dto.BtBacktestRecordDO;
import com.quant.stock.backtest.dto.PortfolioBacktestHistoryRecord;
import com.quant.stock.backtest.dto.SingleBacktestHistoryRecord;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.mapper.BacktestRecordMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 策略总览：按注册策略聚合回测历史 overview（含综合评分）/ 摘要列表 / 详情（内嵌 analysis）。
 */
@Service
public class StrategyEvalService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int SCALE = 6;

    private final StrategyRegistry strategyRegistry;
    private final QuantProperties props;
    private final ObjectProvider<BacktestRecordMapper> mapperProvider;
    private final BackTestHistoryStore historyStore;
    private final BackTestAnalysisStore analysisStore;

    public StrategyEvalService(StrategyRegistry strategyRegistry,
                               QuantProperties props,
                               ObjectProvider<BacktestRecordMapper> mapperProvider,
                               BackTestHistoryStore historyStore,
                               BackTestAnalysisStore analysisStore) {
        this.strategyRegistry = strategyRegistry;
        this.props = props;
        this.mapperProvider = mapperProvider;
        this.historyStore = historyStore;
        this.analysisStore = analysisStore;
    }

    /**
     * 中位数：空/null → null；奇数取正中；偶数取中间两数算术平均（HALF_UP scale 6）。
     */
    public static BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = new ArrayList<BigDecimal>();
        for (BigDecimal v : values) {
            if (v != null) {
                sorted.add(v);
            }
        }
        if (sorted.isEmpty()) {
            return null;
        }
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2).setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal a = sorted.get(n / 2 - 1);
        BigDecimal b = sorted.get(n / 2);
        return a.add(b).divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
    }

    /** 算术平均；空 → null；HALF_UP scale 6。 */
    public static BigDecimal avg(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (BigDecimal v : values) {
            if (v != null) {
                sum = sum.add(v);
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        return sum.divide(BigDecimal.valueOf(count), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 注册表策略列表 + 聚合指标。
     * db 未启用时仍列出 ids，聚合为 0，{@code enabled=false}。
     */
    public Map<String, Object> overview() {
        BacktestRecordMapper mapper = mapperProvider.getIfAvailable();
        boolean enabled = props != null && props.isDbEnabled() && mapper != null;

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("enabled", enabled);

        long unknownCount = 0L;
        if (enabled) {
            unknownCount = mapper.countUnknownStrategy();
        }
        out.put("unknownCount", unknownCount);

        String activeName = strategyRegistry.active().name();
        List<Map<String, Object>> strategies = new ArrayList<Map<String, Object>>();
        for (String id : strategyRegistry.ids()) {
            BaseStrategy strategy = strategyRegistry.resolve(id);
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("strategyId", strategy.name());
            row.put("displayName", strategy.uiLabel());
            row.put("summary", strategy.profileSummary());
            row.put("detailIntro", strategy.detailIntro());
            row.put("active", activeName != null && activeName.equalsIgnoreCase(strategy.name()));

            List<BtBacktestRecordDO> records = enabled
                    ? nullSafe(mapper.selectSummaryByStrategyIds(
                    StrategyIdAliases.matchIdsForQuery(strategy.name()), null))
                    : Collections.<BtBacktestRecordDO>emptyList();

            List<BigDecimal> rates = new ArrayList<BigDecimal>();
            List<BigDecimal> drawdowns = new ArrayList<BigDecimal>();
            List<BigDecimal> winRates = new ArrayList<BigDecimal>();
            int positiveCount = 0;
            int rateCount = 0;
            for (BtBacktestRecordDO r : records) {
                if (r.getTotalRate() != null) {
                    rates.add(r.getTotalRate());
                    rateCount++;
                    if (r.getTotalRate().compareTo(BigDecimal.ZERO) > 0) {
                        positiveCount++;
                    }
                }
                if (r.getMaxDrawdown() != null) {
                    drawdowns.add(r.getMaxDrawdown());
                }
                if (r.getWinRate() != null) {
                    winRates.add(r.getWinRate());
                }
            }

            BigDecimal avgRate = avg(rates);
            BigDecimal avgDd = avg(drawdowns);
            BigDecimal avgWin = avg(winRates);
            BigDecimal positiveRatio = rateCount == 0 ? null
                    : BigDecimal.valueOf(positiveCount)
                    .divide(BigDecimal.valueOf(rateCount), SCALE, RoundingMode.HALF_UP);

            row.put("runCount", records.size());
            row.put("avgTotalRate", avgRate);
            row.put("medianTotalRate", median(rates));
            row.put("avgMaxDrawdown", avgDd);
            row.put("avgWinRate", avgWin);
            row.put("positiveRatio", positiveRatio);

            Map<String, Object> scoreMap = StrategyScoreCalculator.score(
                    avgRate, avgDd, avgWin, positiveRatio, records.size());
            row.put("score", scoreMap.get("score"));
            row.put("scoreMax", scoreMap.get("scoreMax"));
            row.put("grade", scoreMap.get("grade"));
            row.put("scoreNote", scoreMap.get("note"));
            row.put("scoreComponents", scoreMap.get("components"));

            BtBacktestRecordDO last = records.isEmpty() ? null : records.get(0);
            row.put("lastSavedAt", last == null || last.getSavedAt() == null
                    ? null : last.getSavedAt().format(FMT));
            row.put("lastTotalRate", last == null ? null : last.getTotalRate());
            strategies.add(row);
        }
        out.put("strategies", strategies);
        out.put("strategyCount", strategies.size());
        return out;
    }

    /**
     * 某策略摘要历史；未知策略抛 {@link NoSuchElementException}。
     * {@code kind} null/blank/ALL → 不过滤；否则 SINGLE/PORTFOLIO。
     */
    public List<Map<String, Object>> history(String strategyId, String kind) {
        String canonicalId = requireKnownStrategy(strategyId);
        String kindNorm = normalizeKind(kind);
        List<?> list = historyStore.listSummaryByStrategyIds(
                StrategyIdAliases.matchIdsForQuery(canonicalId), kindNorm);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : list) {
            out.add(toSummaryMap(item, false));
        }
        return out;
    }

    /**
     * 单条详情（含 trades / stockResults / analysis）；未知 recordId 抛
     * {@link NoSuchElementException}。
     */
    public Map<String, Object> detail(String recordId) {
        if (!StringUtils.hasText(recordId)) {
            throw new NoSuchElementException("未知回测记录: " + recordId);
        }
        Object rec = historyStore.getByRecordId(recordId.trim());
        if (rec == null) {
            throw new NoSuchElementException("未知回测记录: " + recordId);
        }
        Map<String, Object> out = toSummaryMap(rec, true);
        String kind = String.valueOf(out.get("kind"));
        if ("PORTFOLIO".equalsIgnoreCase(kind)) {
            out.put("analysis", analysisStore.getPortfolioById(recordId.trim()));
        } else {
            out.put("analysis", analysisStore.getSingleById(recordId.trim()));
        }
        return out;
    }

    /**
     * 存在性仅用 {@link StrategyRegistry#contains(String)}；通过后以 {@link StrategyRegistry#resolve(String)}
     * 的 {@link BaseStrategy#name()} 作为 DB 查询用规范 id（大小写与注册表一致）。
     */
    private String requireKnownStrategy(String strategyId) {
        if (!StringUtils.hasText(strategyId) || !strategyRegistry.contains(strategyId)) {
            throw new NoSuchElementException("未知策略: " + strategyId);
        }
        return strategyRegistry.resolve(strategyId.trim()).name();
    }

    /** null/blank/ALL → null（不过滤）；否则大写 SINGLE/PORTFOLIO。 */
    static String normalizeKind(String kind) {
        if (!StringUtils.hasText(kind) || "ALL".equalsIgnoreCase(kind.trim())) {
            return null;
        }
        return kind.trim().toUpperCase();
    }

    private Map<String, Object> toSummaryMap(Object item, boolean withDetail) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (item instanceof SingleBacktestHistoryRecord) {
            SingleBacktestHistoryRecord r = (SingleBacktestHistoryRecord) item;
            m.put("id", r.getId());
            m.put("kind", "SINGLE");
            m.put("savedAt", r.getSavedAt());
            m.put("strategyId", r.getStrategyId());
            m.put("stockCode", r.getStockCode());
            m.put("stockCodes", null);
            m.put("period", r.getPeriod());
            m.put("backStart", r.getBackStart());
            m.put("backEnd", r.getBackEnd());
            m.put("initCapital", r.getInitCapital());
            m.put("finalAsset", r.getFinalAsset());
            m.put("totalRate", r.getTotalRate());
            m.put("maxDrawdown", r.getMaxDrawDown());
            m.put("totalTradeNum", r.getTotalTradeNum());
            m.put("winRate", r.getWinRate());
            m.put("configFingerprint", r.getConfigFingerprint());
            m.put("tradeStats", r.getTradeStats());
            if (withDetail) {
                m.put("trades", r.getTrades());
            }
        } else if (item instanceof PortfolioBacktestHistoryRecord) {
            PortfolioBacktestHistoryRecord r = (PortfolioBacktestHistoryRecord) item;
            m.put("id", r.getId());
            m.put("kind", "PORTFOLIO");
            m.put("savedAt", r.getSavedAt());
            m.put("strategyId", r.getStrategyId());
            m.put("stockCode", null);
            m.put("stockCodes", r.getStockCodeList());
            m.put("period", "DAY");
            m.put("backStart", r.getBackStart());
            m.put("backEnd", r.getBackEnd());
            m.put("initCapital", r.getInitCapital());
            m.put("finalAsset", r.getFinalAsset());
            m.put("totalRate", r.getTotalRate());
            m.put("maxDrawdown", r.getMaxDrawDown());
            m.put("totalTradeNum", r.getTotalTradeNum());
            m.put("winRate", r.getWinRate());
            m.put("configFingerprint", r.getConfigFingerprint());
            m.put("tradeStats", r.getTradeStats());
            if (withDetail) {
                m.put("trades", r.getTrades());
                m.put("stockResults", r.getStockResults());
            }
        }
        return m;
    }

    private static List<BtBacktestRecordDO> nullSafe(List<BtBacktestRecordDO> list) {
        return list == null ? Collections.<BtBacktestRecordDO>emptyList() : list;
    }
}
