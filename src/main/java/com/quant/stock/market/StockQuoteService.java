package com.quant.stock.market;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行情浏览选股表：批量取 {@code market_daily} 最近收盘与涨跌幅（相对上一交易日收盘）。
 * 无库 / 无日线时返回空列表，前端显示「—」。
 */
@Service
public class StockQuoteService {

    private static final int MAX_CODES = 80;
    private static final int SCALE = 4;

    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public StockQuoteService(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    public List<Map<String, Object>> latestQuotes(List<String> codes) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null || codes == null || codes.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> cleaned = new ArrayList<String>();
        for (String raw : codes) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String code = raw.trim();
            if (code.isEmpty()) {
                continue;
            }
            cleaned.add(code);
            if (cleaned.size() >= MAX_CODES) {
                break;
            }
        }
        if (cleaned.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            StringBuilder in = new StringBuilder();
            Object[] args = new Object[cleaned.size()];
            for (int i = 0; i < cleaned.size(); i++) {
                if (i > 0) {
                    in.append(',');
                }
                in.append('?');
                args[i] = cleaned.get(i);
            }
            String sql =
                    "SELECT d.symbol AS code, d.close AS last_close, d.trade_date AS as_of, ("
                            + "SELECT p.close FROM market_daily p "
                            + "WHERE p.symbol = d.symbol AND p.trade_date < d.trade_date "
                            + "ORDER BY p.trade_date DESC LIMIT 1"
                            + ") AS prev_close "
                            + "FROM market_daily d "
                            + "INNER JOIN ("
                            + "  SELECT symbol, MAX(trade_date) AS md FROM market_daily "
                            + "  WHERE symbol IN (" + in + ") GROUP BY symbol"
                            + ") t ON d.symbol = t.symbol AND d.trade_date = t.md";
            List<Map<String, Object>> rows = jdbc.query(sql, args, (rs, rowNum) -> {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                String code = rs.getString("code");
                BigDecimal last = rs.getBigDecimal("last_close");
                BigDecimal prev = rs.getBigDecimal("prev_close");
                Date asOf = rs.getDate("as_of");
                m.put("code", code);
                m.put("lastClose", last);
                m.put("asOf", asOf == null ? null : asOf.toLocalDate().toString());
                BigDecimal pct = null;
                if (last != null && prev != null && prev.compareTo(BigDecimal.ZERO) != 0) {
                    pct = last.subtract(prev).divide(prev, SCALE, RoundingMode.HALF_UP);
                }
                m.put("pctChg", pct);
                return m;
            });
            return rows == null ? Collections.<Map<String, Object>>emptyList() : rows;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> latestQuotesCsv(String codesCsv) {
        if (!StringUtils.hasText(codesCsv)) {
            return Collections.emptyList();
        }
        String[] parts = codesCsv.split("[,\\s]+");
        List<String> list = new ArrayList<String>();
        for (String p : parts) {
            if (StringUtils.hasText(p)) {
                list.add(p.trim());
            }
        }
        return latestQuotes(list);
    }
}
