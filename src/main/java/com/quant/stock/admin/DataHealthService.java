package com.quant.stock.admin;

import com.quant.stock.pool.TradePoolService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 行情健康度分层：
 * <ul>
 *   <li>全市场 / universe：检查 {@code market_daily} 覆盖与滞后</li>
 *   <li>目标池：额外检查 {@code market_1min}（非池标的不因缺分钟告警）</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "quant", name = "db-enabled", havingValue = "true")
public class DataHealthService {

    private static final int DAILY_STALE_DAYS = 5;
    private static final int MINUTE_STALE_HOURS = 48;
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 进度刷新间隔：每检查 N 只更新一次状态文案，避免过度写 volatile */
    private static final int PROGRESS_EVERY = 5;

    private final JdbcTemplate jdbc;
    private final TradePoolService tradePoolService;
    private final Executor executor;

    private final AtomicReference<HealthRunState> runRef = new AtomicReference<HealthRunState>();
    private volatile Map<String, Object> lastResult = new LinkedHashMap<String, Object>();

    public DataHealthService(JdbcTemplate jdbc,
                             TradePoolService tradePoolService,
                             @Qualifier("batchScanExecutor") Executor executor) {
        this.jdbc = jdbc;
        this.tradePoolService = tradePoolService;
        this.executor = executor;
    }

    /** 同步分层健康检查（与 data-validate 同口径）；同时写入 lastResult。 */
    public Map<String, Object> check() {
        return runCheck(null);
    }

    /** 最近一次完成的检查结果（可能为空 map）。 */
    public Map<String, Object> lastResult() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, Object> last = lastResult;
        if (last != null && !last.isEmpty()) {
            out.putAll(last);
        } else {
            out.put("universeSize", 0);
            out.put("okCount", 0);
            out.put("warnCount", 0);
            out.put("items", new ArrayList<Map<String, Object>>());
            out.put("hint", "尚未执行覆盖检查，请点「刷新覆盖检查」");
        }
        HealthRunState s = runRef.get();
        out.put("running", s != null && s.running);
        return out;
    }

    /**
     * 异步启动覆盖检查；进度见 {@link #status()}。
     */
    public Map<String, Object> startAsync() {
        synchronized (runRef) {
            HealthRunState cur = runRef.get();
            if (cur != null && cur.running) {
                throw new IllegalStateException("覆盖检查正在进行中，请稍候");
            }
            final HealthRunState state = new HealthRunState();
            state.running = true;
            state.ok = null;
            state.phase = "starting";
            state.phaseLabel = "已受理";
            state.detail = "准备开始覆盖检查…";
            state.summary = "已受理覆盖检查任务";
            state.startedAt = LocalDateTime.now();
            runRef.set(state);
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Map<String, Object> result = runCheck(state);
                        state.ok = true;
                        state.phase = "done";
                        state.phaseLabel = "已完成";
                        int warn = result.get("warnCount") instanceof Number
                                ? ((Number) result.get("warnCount")).intValue() : 0;
                        int uni = result.get("universeSize") instanceof Number
                                ? ((Number) result.get("universeSize")).intValue() : 0;
                        state.summary = "覆盖检查完成：共 " + uni + " 只，告警 " + warn + " 只";
                        state.detail = state.summary;
                        state.result = result;
                        log.info("数据健康覆盖检查完成: {}", state.summary);
                    } catch (Exception e) {
                        state.ok = false;
                        state.phase = "error";
                        state.phaseLabel = "失败";
                        state.message = e.getMessage() == null ? "覆盖检查失败" : e.getMessage();
                        state.summary = "覆盖检查失败：" + state.message;
                        state.detail = state.summary;
                        log.warn("数据健康覆盖检查失败: {}", state.message);
                    } finally {
                        state.running = false;
                        state.finishedAt = LocalDateTime.now();
                        state.currentCode = null;
                    }
                }
            });
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("async", true);
        out.put("message", "已开始覆盖检查，请查看进度");
        out.put("poll", "/api/ops/data-health/status");
        out.put("status", status());
        return out;
    }

    /** 当前覆盖检查进度（含完成后的 result）。 */
    public Map<String, Object> status() {
        HealthRunState s = runRef.get();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (s == null) {
            m.put("idle", true);
            m.put("running", false);
            Map<String, Object> last = lastResult;
            if (last != null && !last.isEmpty()) {
                m.put("hasLastResult", true);
                m.put("result", last);
            } else {
                m.put("hasLastResult", false);
            }
            return m;
        }
        m.put("idle", false);
        m.put("running", s.running);
        m.put("ok", s.ok);
        m.put("phase", s.phase);
        m.put("phaseLabel", s.phaseLabel);
        m.put("detail", s.detail);
        m.put("summary", s.summary);
        m.put("message", s.message);
        m.put("currentCode", s.currentCode);
        m.put("currentIndex", s.currentIndex);
        m.put("total", s.total);
        m.put("poolSize", s.poolSize);
        m.put("okSoFar", s.okSoFar);
        m.put("warnSoFar", s.warnSoFar);
        m.put("startedAt", s.startedAt == null ? null : s.startedAt.format(DT_FMT));
        m.put("finishedAt", s.finishedAt == null ? null : s.finishedAt.format(DT_FMT));
        int total = Math.max(1, s.total);
        int cur = Math.min(s.currentIndex, total);
        if ("done".equals(s.phase)) {
            cur = total;
        }
        if ("loading".equals(s.phase) || "starting".equals(s.phase)) {
            cur = 0;
        }
        m.put("progressCurrent", cur);
        m.put("progressTotal", total);
        double pct = "loading".equals(s.phase) || "starting".equals(s.phase)
                ? 2.0
                : (100.0 * cur / total);
        if ("done".equals(s.phase)) {
            pct = 100.0;
        }
        m.put("progressPercent", Math.round(pct * 10) / 10.0);
        if (s.result != null) {
            m.put("result", s.result);
            m.put("hasLastResult", true);
        } else if (!s.running && lastResult != null && !lastResult.isEmpty()) {
            m.put("result", lastResult);
            m.put("hasLastResult", true);
        }
        return m;
    }

    private Map<String, Object> runCheck(HealthRunState state) {
        if (state != null) {
            state.phase = "loading";
            state.phaseLabel = "加载标的";
            state.detail = "正在读取全市场股票列表与目标池…";
            state.summary = state.detail;
        }

        List<String> universe = new ArrayList<String>();
        for (Map<String, String> u : tradePoolService.listUniverse()) {
            universe.add(u.get("code"));
        }
        Set<String> pool = new HashSet<String>(tradePoolService.listActiveCodes());
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        boolean anyDaily = tableHasAnyDaily();

        if (state != null) {
            state.total = universe.size();
            state.poolSize = pool.size();
            state.phase = "checking";
            state.phaseLabel = "逐只检查";
            state.detail = "共 " + universe.size() + " 只标的，目标池 " + pool.size() + " 只；开始检查日线/分钟覆盖…";
            state.summary = state.detail;
            state.currentIndex = 0;
        }

        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int ok = 0;
        int warn = 0;
        int dailyWarn = 0;
        int minuteWarn = 0;
        for (int i = 0; i < universe.size(); i++) {
            String code = universe.get(i);
            boolean inPool = pool.contains(code);
            if (state != null) {
                state.currentCode = code;
                state.currentIndex = i + 1;
                if (i % PROGRESS_EVERY == 0 || i + 1 == universe.size()) {
                    String scope = inPool ? "（目标池，查日线+分钟）" : "（全市场，查日线）";
                    state.detail = "正在检查 " + (i + 1) + "/" + universe.size()
                            + " · " + code + " " + scope
                            + " · 目前正常 " + ok + " / 告警 " + warn;
                    state.summary = "逐只检查覆盖 " + (i + 1) + "/" + universe.size();
                    state.okSoFar = ok;
                    state.warnSoFar = warn;
                }
            }
            Map<String, Object> row = checkOne(code, inPool, today, now, anyDaily);
            items.add(row);
            if (Boolean.TRUE.equals(row.get("ok"))) {
                ok++;
            } else {
                warn++;
            }
            if (Boolean.TRUE.equals(row.get("dailyWarn"))) {
                dailyWarn++;
            }
            if (Boolean.TRUE.equals(row.get("minuteWarn"))) {
                minuteWarn++;
            }
        }

        if (state != null) {
            state.phase = "summarizing";
            state.phaseLabel = "汇总";
            state.currentCode = null;
            state.detail = "正在汇总结果…";
            state.summary = state.detail;
            state.okSoFar = ok;
            state.warnSoFar = warn;
            state.currentIndex = universe.size();
        }

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("asOf", now.format(DT_FMT));
        m.put("universeSize", universe.size());
        m.put("poolSize", pool.size());
        m.put("okCount", ok);
        m.put("warnCount", warn);
        m.put("dailyWarnCount", dailyWarn);
        m.put("minuteWarnCount", minuteWarn);
        m.put("dailyStaleDays", DAILY_STALE_DAYS);
        m.put("minuteStaleHours", MINUTE_STALE_HOURS);
        m.put("hint", "分层：universe 查 market_daily；目标池额外查 market_1min。"
                + " 日线灌数 fetch_daily_tdx.py；池内分钟 fetch_min1_tdx.py --from-pool。");
        m.put("items", items);
        lastResult = m;
        return m;
    }

    private Map<String, Object> checkOne(String code, boolean inPool, LocalDate today, LocalDateTime now,
                                       boolean anyDaily) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", code);
        m.put("inPool", inPool);
        List<String> issues = new ArrayList<String>();
        boolean dailyWarn = false;
        boolean minuteWarn = false;
        try {
            Integer dayCnt = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM market_daily WHERE symbol = ?", Integer.class, code);
            LocalDate maxDaily = jdbc.query(
                    "SELECT MAX(trade_date) FROM market_daily WHERE symbol = ?",
                    rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null,
                    code);
            // 无日线表数据时回退：用 1 分钟覆盖日数（演示股兼容）
            if ((dayCnt == null || dayCnt <= 0) && !anyDaily) {
                dayCnt = jdbc.queryForObject(
                        "SELECT COUNT(DISTINCT DATE(trade_time)) FROM market_1min WHERE symbol = ?",
                        Integer.class, code);
                LocalDateTime maxOne = jdbc.query(
                        "SELECT MAX(trade_time) FROM market_1min WHERE symbol = ?",
                        rs -> rs.next() ? rs.getObject(1, LocalDateTime.class) : null,
                        code);
                maxDaily = maxOne == null ? null : maxOne.toLocalDate();
            }
            m.put("dailyCount", dayCnt == null ? 0 : dayCnt);
            m.put("maxDaily", maxDaily == null ? null : maxDaily.toString());

            if (dayCnt == null || dayCnt <= 0 || maxDaily == null) {
                dailyWarn = true;
                issues.add("日线为空");
            } else {
                long lagDays = ChronoUnit.DAYS.between(maxDaily, today);
                m.put("dailyLagDays", lagDays);
                if (lagDays > DAILY_STALE_DAYS) {
                    dailyWarn = true;
                    issues.add("日线滞后" + lagDays + "天");
                }
            }

            Integer oneMinCnt = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM market_1min WHERE symbol = ?", Integer.class, code);
            LocalDateTime maxOneMin = jdbc.query(
                    "SELECT MAX(trade_time) FROM market_1min WHERE symbol = ?",
                    rs -> rs.next() ? rs.getObject(1, LocalDateTime.class) : null,
                    code);
            m.put("oneMinCount", oneMinCnt == null ? 0 : oneMinCnt);
            m.put("maxOneMin", maxOneMin == null ? null : maxOneMin.format(DT_FMT));
            m.put("minuteCount", oneMinCnt == null ? 0 : oneMinCnt);
            m.put("maxMinute", maxOneMin == null ? null : maxOneMin.format(DT_FMT));

            if (inPool) {
                if (oneMinCnt == null || oneMinCnt <= 0 || maxOneMin == null) {
                    minuteWarn = true;
                    issues.add("池内1分钟为空");
                } else {
                    long lagDays = ChronoUnit.DAYS.between(maxOneMin.toLocalDate(), today);
                    if (lagDays > DAILY_STALE_DAYS) {
                        minuteWarn = true;
                        issues.add("池内1分钟覆盖日滞后" + lagDays + "天");
                    }
                    long lagHours = ChronoUnit.HOURS.between(maxOneMin, now);
                    m.put("minuteLagHours", lagHours);
                    if (lagHours > MINUTE_STALE_HOURS) {
                        minuteWarn = true;
                        issues.add("池内1分钟滞后" + lagHours + "小时");
                    }
                }
            }
        } catch (Exception e) {
            issues.add("校验异常: " + e.getMessage());
            dailyWarn = true;
            m.put("dailyCount", 0);
            m.put("minuteCount", 0);
            m.put("oneMinCount", 0);
        }
        m.put("dailyWarn", dailyWarn);
        m.put("minuteWarn", minuteWarn);
        m.put("ok", issues.isEmpty());
        m.put("issues", issues);
        m.put("issueText", issues.isEmpty() ? "正常" : String.join("；", issues));
        return m;
    }

    private boolean tableHasAnyDaily() {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM (SELECT 1 FROM market_daily LIMIT 1) t", Integer.class);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static final class HealthRunState {
        volatile boolean running;
        volatile Boolean ok;
        volatile String phase;
        volatile String phaseLabel;
        volatile String detail;
        volatile String summary;
        volatile String message;
        volatile String currentCode;
        volatile int currentIndex;
        volatile int total;
        volatile int poolSize;
        volatile int okSoFar;
        volatile int warnSoFar;
        volatile LocalDateTime startedAt;
        volatile LocalDateTime finishedAt;
        volatile Map<String, Object> result;
    }
}
