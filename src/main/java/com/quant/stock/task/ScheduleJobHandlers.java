package com.quant.stock.task;

import com.quant.stock.admin.DataReconcileGateService;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.kuangrui.MdsMinuteIngestService;
import com.quant.stock.kuangrui.KuangruiOesOpsFacade;
import com.quant.stock.market.FactorDailyComputeService;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.TdxScriptBackfillService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.pool.TradePoolService;
import com.quant.stock.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 预置定时任务业务处理器（与 {@link StrategyTask} 并列）。
 * <p>
 * 本地可跑通的路径已实现；第三方行情/券商 API 对接点以 {@code TODO(api)} 标注。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class ScheduleJobHandlers {

    /** 日线滞后超过该天数则告警（含周末，粗判） */
    private static final int DAILY_STALE_DAYS = 5;
    /** 分钟线滞后超过该小时数则告警 */
    private static final int MINUTE_STALE_HOURS = 48;

    private final MarketDataService marketDataService;
    private final TradePoolService tradePoolService;
    private final StrategyTask strategyTask;
    private final RedisLockUtil redisLockUtil;
    private final JdbcTemplate jdbcTemplate;
    private final DataReconcileGateService dataReconcileGateService;
    private final MdsMinuteIngestService mdsMinuteIngestService;
    private final QuantProperties quantProperties;
    private final FactorDailyComputeService factorDailyComputeService;
    private final TdxScriptBackfillService tdxScriptBackfillService;
    private final JobProgressHub jobProgressHub;
    private final ObjectProvider<KuangruiOesOpsFacade> kuangruiOesOpsProvider;

    /**
     * 行情采集：优先可选宽睿 MDS（开关开启且 live），否则按股票池刷新本地 K 线缓存/落库。
     */
    public void marketCollect() {
        runWithLock("job:market-collect", 55, new Runnable() {
            @Override
            public void run() {
                if (tryMdsCollect()) {
                    return;
                }
                List<String> codes = new ArrayList<String>();
                for (Map<String, String> u : tradePoolService.listUniverse()) {
                    codes.add(u.get("code"));
                }
                if (codes.isEmpty()) {
                    log.warn("[market-collect] 全市场为空，跳过");
                    return;
                }
                int ok = 0;
                int fail = 0;
                int total = codes.size();
                jobProgressHub.phase("running", "执行中", "行情采集：共 " + total + " 只");
                int i = 0;
                for (String code : codes) {
                    i++;
                    try {
                        List<BarDTO> bars = marketDataService.fetchAndPersistMinute(code);
                        if (bars == null || bars.isEmpty()) {
                            fail++;
                            log.warn("[market-collect] 无数据 code={}", code);
                        } else {
                            ok++;
                            BarDTO last = bars.get(bars.size() - 1);
                            log.debug("[market-collect] code={} bars={} last={}",
                                    code, bars.size(), last.getBarBegin());
                        }
                    } catch (Exception e) {
                        fail++;
                        log.error("[market-collect] 失败 code={}: {}", code, e.getMessage(), e);
                    }
                    jobProgressHub.tick(i, total, code,
                            "行情采集 " + i + "/" + total + " · ok=" + ok + " fail=" + fail);
                }
                log.info("[market-collect] 完成 ok={} fail={} universe={}", ok, fail, codes.size());
                jobProgressHub.tick(total, total, null,
                        "行情采集完成 ok=" + ok + " fail=" + fail);
            }
        });
    }

    /** @return true 表示已走 MDS 路径（含失败日志），不再回退 mock 全扫 */
    private boolean tryMdsCollect() {
        if (!mdsMinuteIngestService.isLive()) {
            return false;
        }
        QuantProperties.Kuangrui k = quantProperties.getKuangrui();
        QuantProperties.Kuangrui.Mds mds = k == null ? null : k.getMds();
        boolean doPull = mds == null || mds.isCollectPull();
        boolean doFlush = mds == null || mds.isCollectFlush();
        try {
            Map<String, Object> st = mdsMinuteIngestService.status();
            boolean subscribed = Boolean.TRUE.equals(st.get("subscribed"));
            int upserted = 0;
            if (subscribed && doFlush) {
                upserted = mdsMinuteIngestService.flushBuckets();
                log.info("[market-collect] MDS 订阅模式 flush upserted={}", upserted);
                return true;
            }
            if (doPull) {
                List<String> codes = new ArrayList<String>();
                for (Map<String, String> u : tradePoolService.listUniverse()) {
                    if (u.get("code") != null) {
                        codes.add(u.get("code"));
                    }
                }
                if (codes.isEmpty()) {
                    codes.addAll(quantProperties.stockCodeList());
                }
                upserted = mdsMinuteIngestService.pullAndPersist(codes);
                log.info("[market-collect] MDS pull codes={} upserted={}", codes.size(), upserted);
                return true;
            }
            if (doFlush) {
                upserted = mdsMinuteIngestService.flushBuckets();
                log.info("[market-collect] MDS flush upserted={}", upserted);
                return true;
            }
        } catch (Exception e) {
            log.error("[market-collect] MDS 路径失败，回退本地: {}", e.getMessage(), e);
            return false;
        }
        return false;
    }

    /**
     * 持仓盈亏同步：本地成本 + 最新价估算市值/浮动盈亏并打日志；
     * 若宽睿 OES 只读 live，额外拉柜台资金/持仓做纸面对账日志（不改本地账本）。
     */
    public void positionPnlSync() {
        runWithLock("job:position-pnl-sync", 50, new Runnable() {
            @Override
            public void run() {
                List<Map<String, Object>> views = strategyTask.listLivePositionViews();
                if (views == null || views.isEmpty()) {
                    log.info("[position-pnl-sync] 当前无持仓");
                } else {
                    BigDecimal totalMv = BigDecimal.ZERO;
                    BigDecimal totalPnl = BigDecimal.ZERO;
                    for (Map<String, Object> row : views) {
                        String code = String.valueOf(row.get("code"));
                        Object volObj = row.get("volume");
                        int shares = volObj instanceof Number ? ((Number) volObj).intValue() : 0;
                        BigDecimal avg = toBd(row.get("avgCost"));
                        BigDecimal px = toBd(row.get("lastPrice"));
                        BigDecimal mv = toBd(row.get("marketValue"));
                        BigDecimal pnl = toBd(row.get("unrealizedPnl"));
                        BigDecimal pnlPct = toBd(row.get("unrealizedPnlPct"));
                        totalMv = totalMv.add(mv);
                        totalPnl = totalPnl.add(pnl);
                        log.info("[position-pnl-sync] {} x{} cost={} mark={} mv={} pnl={} ({})",
                                code, shares,
                                avg.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                                px.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                                mv.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                pnl.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                pnlPct.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                                        .toPlainString() + "%");
                    }
                    log.info("[position-pnl-sync] 持仓只数={} 市值合计≈{} 浮盈合计≈{}",
                            views.size(),
                            totalMv.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                            totalPnl.setScale(2, RoundingMode.HALF_UP).toPlainString());
                }
                KuangruiOesOpsFacade oes = kuangruiOesOpsProvider.getIfAvailable();
                if (oes != null) {
                    oes.logReconcileIfLive("position-pnl-sync");
                }
            }
        });
    }

    private static BigDecimal toBd(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal) {
            return (BigDecimal) o;
        }
        return new BigDecimal(o.toString());
    }

    /**
     * 全市场扫描：筛可入选标的并覆盖唯一目标池（无人工确认）。
     * <p>
     * TODO(api): 全市场行情源就绪后，universe 来自外部列表；筛选因子可接更多 API。
     */
    public void poolRebuild() {
        // 全市场因子预刷 + 粗筛扫描常需数十分钟；锁 TTL 须覆盖整段，避免中途锁过期被二次触发
        runWithLock("job:pool-rebuild", 7200, new Runnable() {
            @Override
            public void run() {
                jobProgressHub.phase("running", "执行中", "全市场入池扫描进行中…");
                // 与目标池页「扫描更新」一致：覆盖池 + 写批次 + PDF 报告
                Map<String, Object> out = tradePoolService.analyzeAndRecommend();
                log.info("[pool-rebuild] selected={} codes={} report={} minuteHint={}",
                        out.get("selected"), out.get("codes"), out.get("reportFileName"),
                        out.get("minuteBackfillHint"));
                jobProgressHub.note("入池完成 selected=" + out.get("selected")
                        + (out.get("batchId") != null ? (" batchId=" + out.get("batchId")) : ""));
            }
        });
    }

    /**
     * 由日线重算 factor_daily（全市场有日线的标的）。
     */
    public void factorDailyRebuild() {
        runWithLock("job:factor-daily-rebuild", 1800, new Runnable() {
            @Override
            public void run() {
                jobProgressHub.phase("running", "执行中", "日频因子重算开始…");
                Map<String, Object> out = factorDailyComputeService.rebuild(null,
                        new FactorDailyComputeService.ProgressCallback() {
                            @Override
                            public void onProgress(int done, int total, String symbol) {
                                jobProgressHub.tick(done, total, symbol,
                                        "因子重算 " + done + "/" + total
                                                + (symbol != null ? (" · " + symbol) : ""));
                            }
                        });
                log.info("[factor-daily-rebuild] {}", out);
                jobProgressHub.tick(
                        out.get("input") instanceof Number ? ((Number) out.get("input")).intValue() : 0,
                        out.get("input") instanceof Number ? ((Number) out.get("input")).intValue() : 0,
                        null,
                        "因子重算完成 ok=" + out.get("ok") + " skip=" + out.get("skip")
                                + " fail=" + out.get("fail"));
            }
        });
    }

    /** 池内分钟 TDX 回填（同步；需 quant.tdx-script.enabled）。 */
    public void poolMinuteBackfill() {
        runWithLock("job:pool-minute-backfill", 3600, new Runnable() {
            @Override
            public void run() {
                Map<String, Object> out = tdxScriptBackfillService.backfillPoolMinuteSync();
                log.info("[pool-minute-backfill] {}", out);
                if (!Boolean.TRUE.equals(out.get("ok"))) {
                    throw new IllegalStateException(String.valueOf(out.get("message")));
                }
            }
        });
    }

    /** 全市场日线 TDX 回填近 1 年（同步；需 quant.tdx-script.enabled）。 */
    public void dayCollect() {
        runWithLock("job:day-collect", 7200, new Runnable() {
            @Override
            public void run() {
                Map<String, Object> out = tdxScriptBackfillService.backfillDailySync(1.0);
                log.info("[day-collect] {}", out);
                if (!Boolean.TRUE.equals(out.get("ok"))) {
                    throw new IllegalStateException(String.valueOf(out.get("message")));
                }
            }
        });
    }

    /**
     * 数据校验分层：universe → market_daily；目标池 → market_1min。
     */
    public void dataValidate() {
        runWithLock("job:data-validate", 120, new Runnable() {
            @Override
            public void run() {
                List<String> universe = new ArrayList<String>();
                for (Map<String, String> u : tradePoolService.listUniverse()) {
                    universe.add(u.get("code"));
                }
                if (universe.isEmpty()) {
                    log.warn("[data-validate] 全市场为空，跳过");
                    return;
                }
                Set<String> pool = new HashSet<String>(tradePoolService.listActiveCodes());
                int dailyWarn = 0;
                int minuteWarn = 0;
                LocalDate today = LocalDate.now();
                LocalDateTime now = LocalDateTime.now();
                int total = universe.size();
                jobProgressHub.phase("running", "执行中", "数据校验：共 " + total + " 只");
                int i = 0;
                for (String code : universe) {
                    i++;
                    try {
                        if (!checkDailyOk(code, today)) {
                            dailyWarn++;
                        }
                        if (pool.contains(code) && !checkMinuteOk(code, today, now)) {
                            minuteWarn++;
                        }
                    } catch (Exception e) {
                        dailyWarn++;
                        log.error("[data-validate] {} 校验异常: {}", code, e.getMessage(), e);
                    }
                    if (i == total || i % 50 == 0) {
                        jobProgressHub.tick(i, total, code,
                                "数据校验 " + i + "/" + total
                                        + " · dailyWarn=" + dailyWarn + " minuteWarn=" + minuteWarn);
                    }
                }
                log.info("[data-validate] 完成 universe={} pool={} dailyWarn={} minuteWarn={}",
                        universe.size(), pool.size(), dailyWarn, minuteWarn);
                jobProgressHub.phase("running", "执行中", "分钟自洽检查…");
                try {
                    List<String> reconCodes = pool.isEmpty() ? universe : new ArrayList<String>(pool);
                    Map<String, Object> recon = dataReconcileGateService.reconcile(reconCodes);
                    log.info("[data-validate] 分钟自洽 diverge={} block={}",
                            recon.get("divergeCodeCount"), recon.get("blockNewOpen"));
                    jobProgressHub.tick(total, total, null,
                            "校验完成 dailyWarn=" + dailyWarn + " minuteWarn=" + minuteWarn
                                    + " · diverge=" + recon.get("divergeCodeCount"));
                } catch (Exception e) {
                    log.error("[data-validate] 分钟自洽失败: {}", e.getMessage(), e);
                    jobProgressHub.tick(total, total, null,
                            "校验完成 dailyWarn=" + dailyWarn + " minuteWarn=" + minuteWarn
                                    + "（自洽失败：" + e.getMessage() + "）");
                }
            }
        });
    }

    private boolean checkDailyOk(String code, LocalDate today) {
        Integer dayCnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM market_daily WHERE symbol = ?", Integer.class, code);
        LocalDate maxDaily = jdbcTemplate.query(
                "SELECT MAX(trade_date) FROM market_daily WHERE symbol = ?",
                rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null,
                code);
        if ((dayCnt == null || dayCnt <= 0) && !hasAnyMarketDaily()) {
            LocalDateTime maxOneMin = jdbcTemplate.query(
                    "SELECT MAX(trade_time) FROM market_1min WHERE symbol = ?",
                    rs -> rs.next() ? rs.getObject(1, LocalDateTime.class) : null,
                    code);
            maxDaily = maxOneMin == null ? null : maxOneMin.toLocalDate();
            dayCnt = maxDaily == null ? 0 : 1;
        }
        if (dayCnt == null || dayCnt <= 0 || maxDaily == null) {
            log.warn("[data-validate] {} 日线为空", code);
            return false;
        }
        long lagDays = ChronoUnit.DAYS.between(maxDaily, today);
        if (lagDays > DAILY_STALE_DAYS) {
            log.warn("[data-validate] {} 日线滞后 {} 天 (last={})", code, lagDays, maxDaily);
            return false;
        }
        return true;
    }

    private boolean checkMinuteOk(String code, LocalDate today, LocalDateTime now) {
        Integer oneMinCnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM market_1min WHERE symbol = ?", Integer.class, code);
        LocalDateTime maxOneMin = jdbcTemplate.query(
                "SELECT MAX(trade_time) FROM market_1min WHERE symbol = ?",
                rs -> rs.next() ? rs.getObject(1, LocalDateTime.class) : null,
                code);
        if (oneMinCnt == null || oneMinCnt <= 0 || maxOneMin == null) {
            log.warn("[data-validate] {} 池内1分钟为空", code);
            return false;
        }
        long lagDays = ChronoUnit.DAYS.between(maxOneMin.toLocalDate(), today);
        if (lagDays > DAILY_STALE_DAYS) {
            log.warn("[data-validate] {} 池内1分钟覆盖日滞后 {} 天 (last={})",
                    code, lagDays, maxOneMin.toLocalDate());
            return false;
        }
        long lagHours = ChronoUnit.HOURS.between(maxOneMin, now);
        if (lagHours > MINUTE_STALE_HOURS) {
            log.warn("[data-validate] {} 池内1分钟滞后 {} 小时 (last={})",
                    code, lagHours, maxOneMin);
            return false;
        }
        return true;
    }

    private boolean hasAnyMarketDaily() {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM (SELECT 1 FROM market_daily LIMIT 1) t", Integer.class);
            return n != null && n > 0;
        } catch (Exception e) {
            log.error("定时任务处理器异常", e);
            return false;
        }
    }

    /**
     * 抢锁执行；抢不到则抛错，便于「执行一次」接口返回失败。
     */
    private void runWithLock(String lockKey, long expireSeconds, Runnable body) {
        if (!redisLockUtil.tryLock(lockKey, expireSeconds)) {
            throw new IllegalStateException("任务忙或锁未释放，请稍后重试（" + lockKey + "）");
        }
        try {
            body.run();
        } finally {
            redisLockUtil.unlock(lockKey);
        }
    }
}
