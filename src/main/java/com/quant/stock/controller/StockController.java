package com.quant.stock.controller;

import com.quant.stock.backtest.BackTestAnalysisStore;
import com.quant.stock.backtest.BackTestEngine;
import com.quant.stock.backtest.BackTestHistoryStore;
import com.quant.stock.backtest.dto.BackTestAnalysisRecord;
import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.backtest.dto.SingleBacktestHistoryRecord;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.BarPeriod;
import com.quant.stock.market.JsonBarDataStore;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.pool.TradePoolService;
import com.quant.stock.session.BranchScaffoldStrategy;
import com.quant.stock.session.SessionBackTestEngine;
import com.quant.stock.session.SessionStrategy;
import com.quant.stock.strategy.BaseStrategy;
import com.quant.stock.strategy.IndicatorSignalUtil;
import com.quant.stock.strategy.StrategyRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行情与单股回测 REST：K 线、数据概览、回测执行及历史/分析查询。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StockController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final QuantProperties quantProperties;
    private final MarketDataService marketDataService;
    private final BackTestEngine backTestEngine;
    private final SessionBackTestEngine sessionBackTestEngine;
    private final BackTestHistoryStore backTestHistoryStore;
    private final BackTestAnalysisStore backTestAnalysisStore;
    private final JsonBarDataStore jsonBarDataStore;
    private final ObjectProvider<TradePoolService> tradePoolServiceProvider;
    private final StrategyRegistry strategyRegistry;

    /**
     * 浏览/回测用股票列表：优先全市场 stock_basic，其次 json 种子，最后 yml stock-codes。
     */
    @GetMapping("/stock/pool")
    public List<Map<String, String>> stockPool() {
        TradePoolService poolSvc = tradePoolServiceProvider.getIfAvailable();
        if (poolSvc != null) {
            List<Map<String, String>> universe = poolSvc.listUniverse();
            if (universe != null && !universe.isEmpty()) {
                return universe;
            }
        }
        if (jsonBarDataStore.available()) {
            return jsonBarDataStore.getStocks();
        }
        List<Map<String, String>> list = new ArrayList<Map<String, String>>();
        for (String code : quantProperties.stockCodeList()) {
            Map<String, String> m = new HashMap<String, String>();
            m.put("code", code);
            m.put("name", code);
            list.add(m);
        }
        return list;
    }

    /** 模拟数据目录概览（不强制加载全部MIN_1） */
    @GetMapping("/data/summary")
    public Map<String, Object> dataSummary() {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("available", jsonBarDataStore.available());
        map.put("stocks", jsonBarDataStore.getStocks());
        if (jsonBarDataStore.getMeta() != null) {
            map.put("start", jsonBarDataStore.getMeta().getString("start"));
            map.put("end", jsonBarDataStore.getMeta().getString("end"));
            map.put("periods", jsonBarDataStore.getMeta().get("periods"));
            map.put("description", jsonBarDataStore.getMeta().getString("description"));
        }
        return map;
    }

    /** 1 分钟 K 线快捷接口（默认截取末尾约 1500 根）。 */
    @GetMapping("/kline/minute")
    public Map<String, Object> minuteKline(@RequestParam("code") String code) {
        return kline(code, BarPeriod.MIN_1.name(), null, null, 1500);
    }

    /**
     * 统一周期K线：period=MIN_1/MIN_5/.../DAY/WEEK/MONTH
     * maxBars：图表点数上限，默认取末尾，避免分钟级全年卡顿
     */
    @GetMapping("/kline")
    public Map<String, Object> kline(@RequestParam("code") String code,
                                     @RequestParam(value = "period", defaultValue = "DAY") String period,
                                     @RequestParam(value = "start", required = false)
                                     @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start,
                                     @RequestParam(value = "end", required = false)
                                     @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end,
                                     @RequestParam(value = "maxBars", required = false) Integer maxBars) {
        BarPeriod barPeriod;
        try {
            barPeriod = BarPeriod.valueOf(period.toUpperCase());
        } catch (Exception e) {
            barPeriod = BarPeriod.DAY;
        }
        List<BarDTO> bars = marketDataService.getKline(code, barPeriod, start, end);
        int total = bars.size();
        int limit = maxBars == null ? defaultMaxBars(barPeriod) : maxBars;
        if (limit > 0 && bars.size() > limit) {
            bars = new ArrayList<BarDTO>(bars.subList(bars.size() - limit, bars.size()));
        }
        Map<String, double[]> indicators = IndicatorSignalUtil.calcSeriesForChart(bars);
        Map<String, Object> resp = new HashMap<String, Object>();
        resp.put("code", code);
        resp.put("period", barPeriod.name());
        resp.put("table", barPeriod.getTableName());
        resp.put("total", total);
        resp.put("returned", bars.size());
        resp.put("bars", bars);
        resp.put("indicators", indicators);
        return resp;
    }

    /**
     * 对单只股票执行当前（或指定）策略回测并持久化历史与分析。
     * <p>
     * {@code engine=classic|session}，默认 classic；{@code strategyId=branchScaffold} 且未显式指定时走 session。
     * session 强制 MIN_1；{@code failOnMissingDep=true} 时缺依赖整单失败。
     */
    @GetMapping("/backtest/run")
    public BackTestResult runBacktest(@RequestParam("code") String code,
                                      @RequestParam(value = "initCapital", required = false) BigDecimal initCapital,
                                      @RequestParam(value = "period", defaultValue = "DAY") String period,
                                      @RequestParam(value = "feeRate", required = false) BigDecimal feeRate,
                                      @RequestParam(value = "strategyId", required = false) String strategyId,
                                      @RequestParam(value = "engine", required = false) String engine,
                                      @RequestParam(value = "failOnMissingDep", defaultValue = "false") boolean failOnMissingDep,
                                      @RequestParam(value = "backStart", required = false)
                                      @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime backStart,
                                      @RequestParam(value = "backEnd", required = false)
                                      @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime backEnd) {
        String engineNorm = resolveEngine(engine, strategyId);
        BigDecimal capital = initCapital == null ? new BigDecimal("100000") : initCapital;
        String startStr = backStart == null ? null : backStart.format(FMT);
        String endStr = backEnd == null ? null : backEnd.format(FMT);

        if ("session".equals(engineNorm)) {
            BaseStrategy resolved = strategyRegistry.resolve(strategyId);
            if (!(resolved instanceof SessionStrategy)) {
                throw new IllegalArgumentException("engine=session 需要实现 SessionStrategy 的策略，当前="
                        + resolved.name());
            }
            BackTestResult result = sessionBackTestEngine.run(
                    code, backStart, backEnd, capital, (SessionStrategy) resolved, failOnMissingDep);
            if (result.getEngine() == null) {
                result.setEngine("session");
            }
            SingleBacktestHistoryRecord hist = backTestHistoryStore.appendSingle(
                    BarPeriod.MIN_1.name(), startStr, endStr, result);
            if (hist != null) {
                backTestAnalysisStore.appendSingle(
                        hist.getId(), hist.getSavedAt(), BarPeriod.MIN_1.name(), startStr, endStr, result);
            }
            return result;
        }

        BarPeriod barPeriod;
        try {
            barPeriod = BarPeriod.valueOf(period.toUpperCase());
        } catch (Exception e) {
            barPeriod = BarPeriod.DAY;
        }
        List<BarDTO> bars = marketDataService.getKline(code, barPeriod, backStart, backEnd);
        BigDecimal fee = feeRate != null ? feeRate : quantProperties.getFeeRate();
        BackTestResult result = backTestEngine.run(code, bars, capital, fee, quantProperties.getSlipPoint(),
                strategyRegistry.resolve(strategyId));
        if (result.getEngine() == null) {
            result.setEngine("classic");
        }
        SingleBacktestHistoryRecord hist = backTestHistoryStore.appendSingle(
                barPeriod.name(), startStr, endStr, result);
        if (hist != null) {
            backTestAnalysisStore.appendSingle(
                    hist.getId(), hist.getSavedAt(), barPeriod.name(), startStr, endStr, result);
        }
        return result;
    }

    /** classic 默认；branchScaffold 未显式指定时强制 session。 */
    private static String resolveEngine(String engine, String strategyId) {
        if (engine != null && !engine.trim().isEmpty()) {
            String e = engine.trim().toLowerCase();
            if ("session".equals(e) || "classic".equals(e)) {
                return e;
            }
            throw new IllegalArgumentException("engine 仅支持 classic|session，当前=" + engine);
        }
        if (strategyId != null && BranchScaffoldStrategy.ID.equalsIgnoreCase(strategyId.trim())) {
            return "session";
        }
        return "classic";
    }

    /** 单股回测历史；传 code 则只返回该股 */
    @GetMapping("/backtest/history")
    public List<SingleBacktestHistoryRecord> singleHistory(
            @RequestParam(value = "code", required = false) String code) {
        return backTestHistoryStore.listSingle(code);
    }

    /** 清除某一股票的全部单股回测记录（同时清除对应分析） */
    @DeleteMapping("/backtest/history")
    public Map<String, Object> clearSingleHistory(@RequestParam("code") String code) {
        int removed = backTestHistoryStore.clearSingleByCode(code);
        int analysisRemoved = backTestAnalysisStore.clearSingleByCode(code);
        Map<String, Object> resp = new HashMap<String, Object>();
        resp.put("code", code);
        resp.put("removed", removed);
        resp.put("analysisRemoved", analysisRemoved);
        return resp;
    }

    /**
     * 单股回测分析：传 id 查与历史一一对应的一条；或传 code 列出该股全部分析。
     */
    @GetMapping("/backtest/analysis")
    public Object singleAnalysis(@RequestParam(value = "id", required = false) String id,
                                 @RequestParam(value = "code", required = false) String code) {
        if (id != null && !id.trim().isEmpty()) {
            BackTestAnalysisRecord one = backTestAnalysisStore.getSingleById(id);
            return one == null ? new HashMap<String, Object>() : one;
        }
        return backTestAnalysisStore.listSingle(code);
    }

    private int defaultMaxBars(BarPeriod period) {
        switch (period) {
            case MIN_1:
                return 1200;
            case MIN_5:
                return 2000;
            case MIN_15:
            case MIN_30:
                return 2500;
            default:
                return 0;
        }
    }
}
