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
 * 行情数据健康度：对各标的日线/分钟覆盖与滞后做只读检查（与 data-validate 任务同口径）。
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
        m.put("hint", "与定时任务 data-validate 同口径；外部行情对账见待办清单。");
        m.put("items", items);
        return m;
    }

    private Map<String, Object> checkOne(String code, LocalDate today, LocalDateTime now) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", code);
        List<String> issues = new ArrayList<String>();
        try {
            Integer dailyCnt = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM market_daily WHERE symbol = ?", Integer.class, code);
            LocalDate maxDaily = jdbc.query(
                    "SELECT MAX(trade_date) FROM market_daily WHERE symbol = ?",
                    rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null,
                    code);
            Integer minuteCnt = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM market_minute WHERE symbol = ?", Integer.class, code);
            LocalDateTime maxMinute = jdbc.query(
                    "SELECT MAX(trade_time) FROM market_minute WHERE symbol = ?",
                    rs -> rs.next() ? rs.getObject(1, LocalDateTime.class) : null,
                    code);

            m.put("dailyCount", dailyCnt == null ? 0 : dailyCnt);
            m.put("maxDaily", maxDaily == null ? null : maxDaily.toString());
            m.put("minuteCount", minuteCnt == null ? 0 : minuteCnt);
            m.put("maxMinute", maxMinute == null ? null : maxMinute.format(DT_FMT));

            if (dailyCnt == null || dailyCnt <= 0 || maxDaily == null) {
                issues.add("日线为空");
            } else {
                long lagDays = ChronoUnit.DAYS.between(maxDaily, today);
                m.put("dailyLagDays", lagDays);
                if (lagDays > DAILY_STALE_DAYS) {
                    issues.add("日线滞后" + lagDays + "天");
                }
            }
            if (minuteCnt == null || minuteCnt <= 0 || maxMinute == null) {
                issues.add("分钟线为空");
            } else {
                long lagHours = ChronoUnit.HOURS.between(maxMinute, now);
                m.put("minuteLagHours", lagHours);
                if (lagHours > MINUTE_STALE_HOURS) {
                    issues.add("分钟线滞后" + lagHours + "小时");
                }
            }
        } catch (Exception e) {
            issues.add("校验异常: " + e.getMessage());
            m.put("dailyCount", 0);
            m.put("minuteCount", 0);
        }
        m.put("ok", issues.isEmpty());
        m.put("issues", issues);
        m.put("issueText", issues.isEmpty() ? "正常" : String.join("；", issues));
        return m;
    }
}
