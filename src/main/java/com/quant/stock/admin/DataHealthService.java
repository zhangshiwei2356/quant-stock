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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 行情健康度分层：
 * <ul>
 *   <li>全市场 / universe：检查 {@code market_daily} 覆盖与滞后</li>
 *   <li>目标池：额外检查 {@code market_1min}（非池标的不因缺分钟告警）</li>
 *   <li>待处置告警进 {@code warnItems}；北交所空 / 疑似退市 / 停牌进 {@code specialItems}</li>
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
            out.put("specialCount", 0);
            out.put("items", new ArrayList<Map<String, Object>>());
            out.put("specialItems", new ArrayList<Map<String, Object>>());
            out.put("itemsScope", "warn_only");
            out.put("hint", "尚未执行覆盖检查，请点「刷新覆盖检查」");
            out.put("specialHint", "特殊项（北交所空/疑似退市/停牌）不计入待处置告警");
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
                        int special = result.get("specialCount") instanceof Number
                                ? ((Number) result.get("specialCount")).intValue() : 0;
                        int uni = result.get("universeSize") instanceof Number
                                ? ((Number) result.get("universeSize")).intValue() : 0;
                        state.summary = "覆盖检查完成：共 " + uni + " 只，待处置告警 " + warn
                                + " 只，特殊项 " + special + " 只（明细分表）";
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
                        log.error("数据健康覆盖检查失败: {}", state.message, e);
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
        m.put("specialSoFar", s.specialSoFar);
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
            state.detail = "正在读取全市场股票列表、目标池与日线汇总…";
            state.summary = state.detail;
        }

        List<Map<String, String>> uniRows = tradePoolService.listUniverse();
        List<String> universe = new ArrayList<String>(uniRows.size());
        Map<String, String> nameByCode = new HashMap<String, String>();
        for (Map<String, String> u : uniRows) {
            String code = u.get("code");
            if (code == null || code.isEmpty()) {
                continue;
            }
            universe.add(code);
            nameByCode.put(code, u.get("name") == null ? code : u.get("name"));
        }
        Set<String> pool = new HashSet<String>(tradePoolService.listActiveCodes());
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        Map<String, long[]> dailyStats = loadDailyStats();
        Map<String, Object[]> minuteStats = loadMinuteStats(pool);

        if (state != null) {
            state.total = universe.size();
            state.poolSize = pool.size();
            state.phase = "checking";
            state.phaseLabel = "逐只检查";
            state.detail = "共 " + universe.size() + " 只标的，目标池 " + pool.size()
                    + " 只；日线表已汇总 " + dailyStats.size() + " 只有数据…";
            state.summary = state.detail;
            state.currentIndex = 0;
        }

        List<Map<String, Object>> warnItems = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> specialItems = new ArrayList<Map<String, Object>>();
        int ok = 0;
        int warn = 0;
        int special = 0;
        int dailyWarn = 0;
        int minuteWarn = 0;
        int emptyDaily = 0;
        int emptyDailyOther = 0;
        int staleDaily = 0;
        int specialSuspended = 0;
        int specialBj = 0;
        int specialDelisted = 0;

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
                            + " · 目前正常 " + ok + " / 待处置 " + warn + " / 特殊 " + special;
                    state.summary = "逐只检查覆盖 " + (i + 1) + "/" + universe.size();
                    state.okSoFar = ok;
                    state.warnSoFar = warn;
                    state.specialSoFar = special;
                }
            }
            Map<String, Object> row = checkOneCached(
                    code, nameByCode.get(code), inPool, today, now,
                    dailyStats.get(code), minuteStats.get(code));
            String severity = row.get("severity") == null ? null : String.valueOf(row.get("severity"));
            if (Boolean.TRUE.equals(row.get("ok"))) {
                ok++;
            } else if ("special".equals(severity)) {
                special++;
                specialItems.add(row);
            } else {
                warn++;
                warnItems.add(row);
            }
            if (Boolean.TRUE.equals(row.get("dailyWarn"))) {
                dailyWarn++;
            }
            if (Boolean.TRUE.equals(row.get("minuteWarn"))) {
                minuteWarn++;
            }
            String emptyKind = row.get("emptyDailyKind") == null ? null : String.valueOf(row.get("emptyDailyKind"));
            if ("bj".equals(emptyKind)) {
                emptyDaily++;
                specialBj++;
            } else if ("likely_delisted".equals(emptyKind)) {
                emptyDaily++;
                specialDelisted++;
            } else if ("missing".equals(emptyKind)) {
                emptyDaily++;
                emptyDailyOther++;
            }
            if (Boolean.TRUE.equals(row.get("specialSuspended"))) {
                specialSuspended++;
            }
            if (Boolean.TRUE.equals(row.get("dailyStale"))) {
                staleDaily++;
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
            state.specialSoFar = special;
            state.currentIndex = universe.size();
        }

        Map<String, Object> breakdown = new LinkedHashMap<String, Object>();
        breakdown.put("emptyDaily", emptyDaily);
        breakdown.put("emptyDailyBj", specialBj);
        breakdown.put("emptyDailyLikelyDelisted", specialDelisted);
        breakdown.put("emptyDailyOther", emptyDailyOther);
        breakdown.put("staleDaily", staleDaily);
        breakdown.put("minuteWarn", minuteWarn);
        breakdown.put("specialSuspended", specialSuspended);
        breakdown.put("specialBj", specialBj);
        breakdown.put("specialDelisted", specialDelisted);
        breakdown.put("symbolsInMarketDaily", dailyStats.size());

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("asOf", now.format(DT_FMT));
        m.put("universeSize", universe.size());
        m.put("poolSize", pool.size());
        m.put("okCount", ok);
        m.put("warnCount", warn);
        m.put("specialCount", special);
        m.put("dailyWarnCount", dailyWarn);
        m.put("minuteWarnCount", minuteWarn);
        m.put("dailyStaleDays", DAILY_STALE_DAYS);
        m.put("minuteStaleHours", MINUTE_STALE_HOURS);
        m.put("breakdown", breakdown);
        m.put("itemsScope", "warn_only");
        m.put("hint", buildHint(breakdown, dailyStats.size(), universe.size(), ok, warn, special));
        m.put("specialHint", "特殊项：北交所空日线 / 疑似退市·PT / 停牌（名称含「停牌」或最新日线量≤0）。"
                + " 不计入待处置告警，一般无需 day-collect 抢修；待处置告警=可行动的缺数/真滞后/池内分钟问题。");
        m.put("items", warnItems);
        m.put("specialItems", specialItems);
        lastResult = m;
        return m;
    }

    private String buildHint(Map<String, Object> breakdown, int symbolsInDaily, int universe,
                             int ok, int warn, int special) {
        return "分层：universe 查 market_daily；目标池额外查 market_1min。"
                + " 待处置告警 " + warn + " 只进告警表；特殊项 " + special
                + " 只（北交所/退市·PT/停牌）另表展示，不计入告警数。"
                + " 正常 " + ok + " 只不列表。"
                + " 库中有日线标的 " + symbolsInDaily + " 只 / 全市场 " + universe + " 只；"
                + " 日线空 " + breakdown.get("emptyDaily")
                + "（北交所 " + breakdown.get("specialBj")
                + "、疑似退市/PT " + breakdown.get("specialDelisted")
                + "、其它缺数 " + breakdown.get("emptyDailyOther") + "）；"
                + " 停牌特殊 " + breakdown.get("specialSuspended") + "。"
                + " 「日线为空」= 库中该 symbol 行数为 0（非误报）。"
                + " 修复：沪深缺数重跑 day-collect；北交所暂非 TDX 覆盖；幽灵代码"
                + " python scripts/sync_stock_basic.py --deactivate-missing。"
                + " 池内分钟：fetch_min1_tdx.py --from-pool。";
    }

    /** symbol -> [count, epochDay of max trade_date, lastVol]；无数据不入 map。 */
    private Map<String, long[]> loadDailyStats() {
        Map<String, long[]> map = new HashMap<String, long[]>();
        try {
            jdbc.query(
                    "SELECT d.symbol, d.cnt, d.mx, COALESCE(v.volume, 0) last_vol "
                            + "FROM (SELECT symbol, COUNT(1) cnt, MAX(trade_date) mx "
                            + "FROM market_daily GROUP BY symbol) d "
                            + "LEFT JOIN market_daily v ON v.symbol = d.symbol AND v.trade_date = d.mx",
                    rs -> {
                        while (rs.next()) {
                            String sym = rs.getString(1);
                            long cnt = rs.getLong(2);
                            java.sql.Date mx = rs.getDate(3);
                            long lastVol = rs.getLong(4);
                            if (sym == null || cnt <= 0 || mx == null) {
                                continue;
                            }
                            map.put(sym, new long[]{cnt, mx.toLocalDate().toEpochDay(), lastVol});
                        }
                        return null;
                    });
        } catch (Exception e) {
            log.error("批量加载 market_daily 汇总失败，将回退逐只查询: {}", e.getMessage(), e);
        }
        return map;
    }

    /** symbol -> [count, max trade_time]。仅池内需要。 */
    private Map<String, Object[]> loadMinuteStats(Set<String> pool) {
        Map<String, Object[]> map = new HashMap<String, Object[]>();
        if (pool == null || pool.isEmpty()) {
            return map;
        }
        List<String> codes = new ArrayList<String>(pool);
        final int chunk = 400;
        try {
            for (int from = 0; from < codes.size(); from += chunk) {
                List<String> part = codes.subList(from, Math.min(from + chunk, codes.size()));
                StringBuilder in = new StringBuilder();
                List<Object> args = new ArrayList<Object>(part.size());
                for (int i = 0; i < part.size(); i++) {
                    if (i > 0) {
                        in.append(',');
                    }
                    in.append('?');
                    args.add(part.get(i));
                }
                jdbc.query(
                        "SELECT symbol, COUNT(1) cnt, MAX(trade_time) mx FROM market_1min"
                                + " WHERE symbol IN (" + in + ") GROUP BY symbol",
                        args.toArray(),
                        rs -> {
                            while (rs.next()) {
                                String sym = rs.getString(1);
                                int cnt = rs.getInt(2);
                                java.sql.Timestamp mx = rs.getTimestamp(3);
                                if (sym == null || cnt <= 0 || mx == null) {
                                    continue;
                                }
                                map.put(sym, new Object[]{cnt, mx.toLocalDateTime()});
                            }
                            return null;
                        });
            }
        } catch (Exception e) {
            log.error("批量加载 market_1min 汇总失败: {}", e.getMessage(), e);
        }
        return map;
    }

    private Map<String, Object> checkOneCached(String code, String name, boolean inPool,
                                              LocalDate today, LocalDateTime now,
                                              long[] daily, Object[] minute) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", code);
        m.put("name", name);
        m.put("inPool", inPool);
        List<String> issues = new ArrayList<String>();
        boolean dailyWarn = false;
        boolean minuteWarn = false;
        boolean dailyStale = false;
        boolean special = false;
        boolean specialSuspendedFlag = false;

        long dayCnt = daily == null ? 0L : daily[0];
        LocalDate maxDaily = daily == null ? null : LocalDate.ofEpochDay(daily[1]);
        long lastVol = daily == null ? 0L : daily[2];
        m.put("dailyCount", dayCnt);
        m.put("maxDaily", maxDaily == null ? null : maxDaily.toString());
        m.put("lastDailyVolume", lastVol);

        if (dayCnt <= 0 || maxDaily == null) {
            String kind = classifyEmptyDaily(code, name);
            m.put("emptyDailyKind", kind);
            if ("bj".equals(kind)) {
                special = true;
                issues.add("日线为空（北交所，当前 TDX 日线未覆盖）");
            } else if ("likely_delisted".equals(kind)) {
                special = true;
                issues.add("日线为空（名称含退市/PT等，库中确无0行）");
            } else if (isSuspendedName(name)) {
                special = true;
                specialSuspendedFlag = true;
                m.put("emptyDailyKind", "suspended");
                issues.add("日线为空（名称含停牌）");
            } else {
                dailyWarn = true;
                issues.add("日线为空（库中该代码0行，可重跑 day-collect）");
            }
        } else {
            long lagDays = ChronoUnit.DAYS.between(maxDaily, today);
            m.put("dailyLagDays", lagDays);
            boolean suspended = isSuspendedName(name) || lastVol <= 0L;
            if (suspended) {
                special = true;
                specialSuspendedFlag = true;
                if (isSuspendedName(name)) {
                    issues.add("名称含停牌");
                }
                if (lastVol <= 0L) {
                    issues.add("最新日线成交量≤0（停牌/无量）");
                }
                if (lagDays > DAILY_STALE_DAYS) {
                    issues.add("日线滞后" + lagDays + "天（停牌，不计入待处置）");
                }
            } else if (lagDays > DAILY_STALE_DAYS) {
                dailyWarn = true;
                dailyStale = true;
                issues.add("日线滞后" + lagDays + "天");
            }
        }
        m.put("dailyStale", dailyStale);
        m.put("specialSuspended", specialSuspendedFlag);

        int oneMinCnt = 0;
        LocalDateTime maxOneMin = null;
        if (minute != null) {
            oneMinCnt = (Integer) minute[0];
            maxOneMin = (LocalDateTime) minute[1];
        }
        m.put("oneMinCount", oneMinCnt);
        m.put("minuteCount", oneMinCnt);
        m.put("maxOneMin", maxOneMin == null ? null : maxOneMin.format(DT_FMT));
        m.put("maxMinute", maxOneMin == null ? null : maxOneMin.format(DT_FMT));

        // 特殊项（北交所/退市/停牌）不把池内分钟问题计入待处置告警
        if (inPool && !special) {
            if (oneMinCnt <= 0 || maxOneMin == null) {
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
        } else if (inPool && special) {
            if (oneMinCnt <= 0 || maxOneMin == null) {
                issues.add("池内1分钟为空（特殊项附注）");
            }
        }

        m.put("dailyWarn", dailyWarn);
        m.put("minuteWarn", minuteWarn);
        boolean actionable = dailyWarn || minuteWarn;
        if (issues.isEmpty()) {
            m.put("ok", true);
            m.put("severity", "ok");
            m.put("actionNeeded", false);
        } else if (special && !actionable) {
            m.put("ok", false);
            m.put("severity", "special");
            m.put("actionNeeded", false);
        } else {
            // 有可行动问题则进待处置（即使同时带特殊标签也不进 specialItems）
            m.put("ok", false);
            m.put("severity", "warn");
            m.put("actionNeeded", true);
            // 若同时被标 special（理论上 special&&actionable 仅在未来扩展），仍以 warn 为准
        }
        m.put("issues", issues);
        m.put("issueText", issues.isEmpty() ? "正常" : String.join("；", issues));
        return m;
    }

    static String classifyEmptyDaily(String code, String name) {
        if (code != null && (code.startsWith("8") || code.startsWith("4"))) {
            return "bj";
        }
        String n = name == null ? "" : name;
        String upper = n.toUpperCase(Locale.ROOT);
        if (n.contains("退") || n.contains("摘牌") || upper.startsWith("PT") || upper.contains("PT")
                || upper.contains("退市")) {
            return "likely_delisted";
        }
        return "missing";
    }

    /** 证券简称是否含停牌字样。 */
    static boolean isSuspendedName(String name) {
        return name != null && name.contains("停牌");
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
        volatile int specialSoFar;
        volatile LocalDateTime startedAt;
        volatile LocalDateTime finishedAt;
        volatile Map<String, Object> result;
    }
}
