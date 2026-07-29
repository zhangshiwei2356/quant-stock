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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行情数据健康度：对各标的 {@code market_1min} 覆盖与滞后做只读检查（与 data-validate 任务同口径）。
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

    /** 对 universe 内各标的检查 1 分钟覆盖与滞后 */
    public Map<String, Object> check() {
        List<String> codes = new ArrayList<String>();
        for (Map<String, String> u : tradePoolService.listUniverse()) {
            codes.add(u.get("code"));
        }
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int ok = 0;
        int warn = 0;
        for (String code : codes) {
            Map<String, Object> row = checkOne(code, today, now);
            items.add(row);
            if (Boolean.TRUE.equals(row.get("ok"))) {
                ok++;
            } else {
                warn++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("asOf", now.format(DT_FMT));
        m.put("universeSize", codes.size());
        m.put("okCount", ok);
        m.put("warnCount", warn);
        m.put("dailyStaleDays", DAILY_STALE_DAYS);
        m.put("minuteStaleHours", MINUTE_STALE_HOURS);
        m.put("hint", "与定时任务 data-validate 同口径（仅检查 market_1min）；外部行情对账见「能力与待办」。");
        m.put("items", items);
        return m;
    }

    private Map<String, Object> checkOne(String code, LocalDate today, LocalDateTime now) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", code);
        List<String> issues = new ArrayList<String>();
        try {
            Integer oneMinCnt = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM market_1min WHERE symbol = ?", Integer.class, code);
            LocalDateTime maxOneMin = jdbc.query(
                    "SELECT MAX(trade_time) FROM market_1min WHERE symbol = ?",
                    rs -> rs.next() ? rs.getObject(1, LocalDateTime.class) : null,
                    code);
            Integer dayCnt = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT DATE(trade_time)) FROM market_1min WHERE symbol = ?",
                    Integer.class, code);
            LocalDate maxDay = maxOneMin == null ? null : maxOneMin.toLocalDate();

            m.put("oneMinCount", oneMinCnt == null ? 0 : oneMinCnt);
            m.put("maxOneMin", maxOneMin == null ? null : maxOneMin.format(DT_FMT));
            m.put("dailyCount", dayCnt == null ? 0 : dayCnt);
            m.put("maxDaily", maxDay == null ? null : maxDay.toString());
            // 兼容旧前端字段名：minute* 现指向 1 分钟原始层
            m.put("minuteCount", oneMinCnt == null ? 0 : oneMinCnt);
            m.put("maxMinute", maxOneMin == null ? null : maxOneMin.format(DT_FMT));

            if (oneMinCnt == null || oneMinCnt <= 0 || maxOneMin == null) {
                issues.add("1分钟为空");
            } else {
                long lagDays = ChronoUnit.DAYS.between(maxDay, today);
                m.put("dailyLagDays", lagDays);
                if (lagDays > DAILY_STALE_DAYS) {
                    issues.add("1分钟覆盖日滞后" + lagDays + "天");
                }
                long lagHours = ChronoUnit.HOURS.between(maxOneMin, now);
                m.put("minuteLagHours", lagHours);
                if (lagHours > MINUTE_STALE_HOURS) {
                    issues.add("1分钟滞后" + lagHours + "小时");
                }
            }
        } catch (Exception e) {
            issues.add("校验异常: " + e.getMessage());
            m.put("oneMinCount", 0);
            m.put("dailyCount", 0);
            m.put("minuteCount", 0);
        }
        m.put("ok", issues.isEmpty());
        m.put("issues", issues);
        m.put("issueText", issues.isEmpty() ? "正常" : String.join("；", issues));
        return m;
    }
}
