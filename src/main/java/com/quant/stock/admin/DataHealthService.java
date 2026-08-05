package com.quant.stock.admin;

import com.quant.stock.pool.TradePoolService;
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

/**
 * 行情健康度分层：
 * <ul>
 *   <li>全市场 / universe：检查 {@code market_daily} 覆盖与滞后</li>
 *   <li>目标池：额外检查 {@code market_1min}（非池标的不因缺分钟告警）</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "quant", name = "db-enabled", havingValue = "true")
public class DataHealthService {

    private static final int DAILY_STALE_DAYS = 5;
    private static final int MINUTE_STALE_HOURS = 48;
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final TradePoolService tradePoolService;

    public DataHealthService(JdbcTemplate jdbc, TradePoolService tradePoolService) {
        this.jdbc = jdbc;
        this.tradePoolService = tradePoolService;
    }

    /** 分层健康检查（与 data-validate 同口径）。 */
    public Map<String, Object> check() {
        List<String> universe = new ArrayList<String>();
        for (Map<String, String> u : tradePoolService.listUniverse()) {
            universe.add(u.get("code"));
        }
        Set<String> pool = new HashSet<String>(tradePoolService.listActiveCodes());
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int ok = 0;
        int warn = 0;
        int dailyWarn = 0;
        int minuteWarn = 0;
        for (String code : universe) {
            boolean inPool = pool.contains(code);
            Map<String, Object> row = checkOne(code, inPool, today, now);
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
        return m;
    }

    private Map<String, Object> checkOne(String code, boolean inPool, LocalDate today, LocalDateTime now) {
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
            if ((dayCnt == null || dayCnt <= 0) && !tableHasAnyDaily()) {
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
}
