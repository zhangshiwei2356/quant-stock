package com.quant.stock.strategy;

import com.quant.stock.admin.EffectiveParamsService;
import com.quant.stock.admin.ParamsScope;
import com.quant.stock.backtest.BackTestAnalysisStore;
import com.quant.stock.backtest.BackTestEngine;
import com.quant.stock.backtest.BackTestHistoryStore;
import com.quant.stock.backtest.PortfolioBackTestEngine;
import com.quant.stock.backtest.dto.BackTestQueryDTO;
import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.backtest.dto.PortfolioBacktestHistoryRecord;
import com.quant.stock.backtest.dto.PortfolioResultDTO;
import com.quant.stock.backtest.dto.SingleBacktestHistoryRecord;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.mapper.BacktestRecordMapper;
import com.quant.stock.market.BarPeriod;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.pool.TradePoolService;
import com.quant.stock.session.BranchScaffoldStrategy;
import com.quant.stock.session.SessionBackTestEngine;
import com.quant.stock.session.SessionStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 无回测策略的目标池补种：对活跃目标池逐只单股回测，再跑一次全池组合回测并落库。
 */
@Slf4j
@Service
public class StrategyPoolSeedService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BigDecimal DEFAULT_CAPITAL = new BigDecimal("100000");

    private final StrategyRegistry strategyRegistry;
    private final QuantProperties props;
    private final ObjectProvider<TradePoolService> tradePoolProvider;
    private final ObjectProvider<BacktestRecordMapper> mapperProvider;
    private final MarketDataService marketDataService;
    private final BackTestEngine backTestEngine;
    private final SessionBackTestEngine sessionBackTestEngine;
    private final PortfolioBackTestEngine portfolioBackTestEngine;
    private final BackTestHistoryStore historyStore;
    private final BackTestAnalysisStore analysisStore;
    private final EffectiveParamsService effectiveParamsService;
    private final Executor executor;

    private final AtomicReference<SeedState> stateRef = new AtomicReference<SeedState>();

    public StrategyPoolSeedService(StrategyRegistry strategyRegistry,
                                   QuantProperties props,
                                   ObjectProvider<TradePoolService> tradePoolProvider,
                                   ObjectProvider<BacktestRecordMapper> mapperProvider,
                                   MarketDataService marketDataService,
                                   BackTestEngine backTestEngine,
                                   SessionBackTestEngine sessionBackTestEngine,
                                   PortfolioBackTestEngine portfolioBackTestEngine,
                                   BackTestHistoryStore historyStore,
                                   BackTestAnalysisStore analysisStore,
                                   EffectiveParamsService effectiveParamsService,
                                   @Qualifier("batchScanExecutor") Executor executor) {
        this.strategyRegistry = strategyRegistry;
        this.props = props;
        this.tradePoolProvider = tradePoolProvider;
        this.mapperProvider = mapperProvider;
        this.marketDataService = marketDataService;
        this.backTestEngine = backTestEngine;
        this.sessionBackTestEngine = sessionBackTestEngine;
        this.portfolioBackTestEngine = portfolioBackTestEngine;
        this.historyStore = historyStore;
        this.analysisStore = analysisStore;
        this.effectiveParamsService = effectiveParamsService;
        this.executor = executor;
    }

    /**
     * 异步启动目标池补回测。默认仅当该策略尚无回测记录时允许；{@code force=true} 可强制再跑。
     */
    public Map<String, Object> start(String strategyId, boolean force) {
        if (!props.isDbEnabled()) {
            throw new IllegalStateException("数据库未启用，无法补种回测");
        }
        if (!StringUtils.hasText(strategyId) || !strategyRegistry.contains(strategyId)) {
            throw new NoSuchElementException("未知策略: " + strategyId);
        }
        BaseStrategy strategy = strategyRegistry.resolve(strategyId.trim());
        String canonicalId = strategy.name();

        int existing = countRuns(canonicalId);
        if (existing > 0 && !force) {
            throw new IllegalStateException("策略 " + canonicalId + " 已有 " + existing
                    + " 条回测；若仍要补种请传 force=true");
        }

        TradePoolService pool = tradePoolProvider.getIfAvailable();
        if (pool == null) {
            throw new IllegalStateException("目标池服务不可用（需 quant.db-enabled=true）");
        }
        List<String> codes = pool.listActiveCodes();
        if (codes == null || codes.isEmpty()) {
            throw new IllegalStateException("目标池为空，请先重建/扫描入池后再补回测");
        }

        synchronized (stateRef) {
            SeedState cur = stateRef.get();
            if (cur != null && cur.running) {
                throw new IllegalStateException("已有补种任务进行中: " + cur.strategyId
                        + "（请等待完成后再点）");
            }
            final SeedState state = new SeedState();
            state.running = true;
            state.ok = null;
            state.strategyId = canonicalId;
            state.phase = "starting";
            state.phaseLabel = "已受理";
            state.poolSize = codes.size();
            state.singleTotal = codes.size();
            state.singleDone = 0;
            state.singleOk = 0;
            state.singleFail = 0;
            state.portfolioOk = false;
            state.startedAt = LocalDateTime.now();
            state.summary = "已开始对「" + canonicalId + "」用目标池补回测（"
                    + codes.size() + " 只单股 + 1 次组合）";
            state.message = state.summary;
            stateRef.set(state);

            final List<String> codeList = new ArrayList<String>(codes);
            final BaseStrategy strat = strategy;
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    runSeed(state, strat, codeList);
                }
            });
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("async", true);
        out.put("strategyId", canonicalId);
        out.put("poolSize", codes.size());
        out.put("force", force);
        out.put("message", "已开始目标池补回测，请查看进度");
        out.put("poll", "/api/strategy/seed-status");
        out.put("status", status());
        return out;
    }

    /** 当前补种进度快照。 */
    public Map<String, Object> status() {
        SeedState s = stateRef.get();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (s == null) {
            m.put("running", false);
            m.put("idle", true);
            return m;
        }
        m.put("idle", false);
        m.put("running", s.running);
        m.put("ok", s.ok);
        m.put("strategyId", s.strategyId);
        m.put("phase", s.phase);
        m.put("phaseLabel", s.phaseLabel);
        m.put("summary", s.summary);
        m.put("message", s.message);
        m.put("poolSize", s.poolSize);
        m.put("singleTotal", s.singleTotal);
        m.put("singleDone", s.singleDone);
        m.put("singleOk", s.singleOk);
        m.put("singleFail", s.singleFail);
        m.put("portfolioOk", s.portfolioOk);
        m.put("currentCode", s.currentCode);
        m.put("startedAt", s.startedAt == null ? null : s.startedAt.format(FMT));
        m.put("finishedAt", s.finishedAt == null ? null : s.finishedAt.format(FMT));
        int totalSteps = Math.max(1, s.singleTotal + 1);
        int doneSteps = s.singleDone;
        if ("portfolio".equals(s.phase)) {
            doneSteps = s.singleTotal;
        } else if ("done".equals(s.phase)) {
            doneSteps = totalSteps;
        } else if ("error".equals(s.phase) && s.singleDone >= s.singleTotal) {
            doneSteps = s.singleTotal + (Boolean.TRUE.equals(s.portfolioOk) ? 1 : 0);
        }
        m.put("progressCurrent", Math.min(doneSteps, totalSteps));
        m.put("progressTotal", totalSteps);
        double pct = 100.0 * Math.min(doneSteps, totalSteps) / totalSteps;
        m.put("progressPercent", Math.round(pct * 10) / 10.0);
        return m;
    }

    private void runSeed(SeedState state, BaseStrategy strategy, List<String> codes) {
        String sid = strategy.name();
        try {
            state.phase = "single";
            state.phaseLabel = "单股回测";
            boolean useSession = strategy instanceof SessionStrategy
                    || BranchScaffoldStrategy.ID.equalsIgnoreCase(sid);

            for (int i = 0; i < codes.size(); i++) {
                String code = codes.get(i);
                state.currentCode = code;
                state.summary = "单股 " + (i + 1) + "/" + codes.size() + " · " + code;
                state.message = state.summary;
                try {
                    runOneSingle(strategy, code, useSession);
                    state.singleOk++;
                } catch (Exception e) {
                    state.singleFail++;
                    log.warn("策略补种单股失败 {} {}: {}", sid, code, e.getMessage());
                } finally {
                    state.singleDone = i + 1;
                }
            }

            state.phase = "portfolio";
            state.phaseLabel = "组合回测";
            state.currentCode = null;
            state.summary = "组合回测 · 全池 " + codes.size() + " 只";
            state.message = state.summary;
            try {
                runPortfolio(strategy, codes, useSession);
                state.portfolioOk = true;
            } catch (Exception e) {
                state.portfolioOk = false;
                throw new IllegalStateException("组合回测失败: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
            }

            state.ok = true;
            state.phase = "done";
            state.phaseLabel = "已完成";
            state.summary = "补种完成：单股成功 " + state.singleOk + " / 失败 " + state.singleFail
                    + "，组合" + (state.portfolioOk ? "成功" : "失败");
            state.message = state.summary;
            log.info("策略目标池补种完成 {}: {}", sid, state.summary);
        } catch (Exception e) {
            state.ok = false;
            state.phase = "error";
            state.phaseLabel = "失败";
            state.message = e.getMessage() == null ? "补种失败" : e.getMessage();
            state.summary = "「" + sid + "」补种失败：" + state.message;
            log.warn("策略目标池补种失败 {}: {}", sid, state.message);
        } finally {
            state.running = false;
            state.finishedAt = LocalDateTime.now();
            state.currentCode = null;
        }
    }

    private void runOneSingle(BaseStrategy strategy, String code, boolean useSession) {
        BigDecimal capital = DEFAULT_CAPITAL;
        String resolvedId = strategy.name();
        if (useSession) {
            if (!(strategy instanceof SessionStrategy)) {
                throw new IllegalStateException("session 引擎需要 SessionStrategy，当前=" + resolvedId);
            }
            final SessionStrategy ss = (SessionStrategy) strategy;
            BackTestResult result = ParamsScope.call(
                    effectiveParamsService.resolve(resolvedId, Collections.<String, String>emptyMap()),
                    new java.util.concurrent.Callable<BackTestResult>() {
                        @Override
                        public BackTestResult call() {
                            return sessionBackTestEngine.run(
                                    code, null, null, capital, ss, false);
                        }
                    });
            if (result.getEngine() == null) {
                result.setEngine("session");
            }
            SingleBacktestHistoryRecord hist = historyStore.appendSingle(
                    BarPeriod.MIN_1.name(), null, null, result, resolvedId);
            if (hist != null) {
                analysisStore.appendSingle(
                        hist.getId(), hist.getSavedAt(), BarPeriod.MIN_1.name(), null, null, result);
            }
            return;
        }

        List<BarDTO> bars = marketDataService.getKline(code, BarPeriod.DAY, null, null);
        if (bars == null || bars.size() < 20) {
            throw new IllegalStateException("日线不足（需≥20）");
        }
        BackTestResult result = backTestEngine.run(code, bars, capital, null, props.getSlipPoint(),
                strategy, Collections.<String, String>emptyMap());
        if (result.getEngine() == null) {
            result.setEngine("classic");
        }
        SingleBacktestHistoryRecord hist = historyStore.appendSingle(
                BarPeriod.DAY.name(), null, null, result, resolvedId);
        if (hist != null) {
            analysisStore.appendSingle(
                    hist.getId(), hist.getSavedAt(), BarPeriod.DAY.name(), null, null, result);
        }
    }

    private void runPortfolio(BaseStrategy strategy, List<String> codes, boolean useSession) {
        BackTestQueryDTO query = BackTestQueryDTO.builder()
                .stockCodeList(codes)
                .initCapital(DEFAULT_CAPITAL)
                .strategyId(strategy.name())
                .engine(useSession ? "session" : "classic")
                .failOnMissingDep(false)
                .build();
        PortfolioResultDTO result = portfolioBackTestEngine.run(query);
        String resolvedId = strategy.name();
        PortfolioBacktestHistoryRecord hist = historyStore.appendPortfolio(query, result, resolvedId);
        if (hist != null) {
            analysisStore.appendPortfolio(hist.getId(), hist.getSavedAt(), query, result);
        }
    }

    private int countRuns(String canonicalId) {
        BacktestRecordMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            return 0;
        }
        List<?> rows = mapper.selectSummaryByStrategyIds(
                StrategyIdAliases.matchIdsForQuery(canonicalId), null);
        return rows == null ? 0 : rows.size();
    }

    private static final class SeedState {
        volatile boolean running;
        volatile Boolean ok;
        volatile String strategyId;
        volatile String phase;
        volatile String phaseLabel;
        volatile String summary;
        volatile String message;
        volatile String currentCode;
        volatile int poolSize;
        volatile int singleTotal;
        volatile int singleDone;
        volatile int singleOk;
        volatile int singleFail;
        volatile Boolean portfolioOk;
        volatile LocalDateTime startedAt;
        volatile LocalDateTime finishedAt;
    }
}
