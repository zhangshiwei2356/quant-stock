package com.quant.stock.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;

/**
 * 风控事件落库 risk_control_log。
 */
@Service
@ConditionalOnProperty(prefix = "quant", name = "db-enabled", havingValue = "true")
public class RiskControlLogService {

    private static final Logger log = LoggerFactory.getLogger(RiskControlLogService.class);
    private static final String ACCOUNT_ID = "LIVE";

    private final JdbcTemplate jdbc;

    public RiskControlLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(LocalDate logDate, String symbol, String ruleType,
                       BigDecimal triggerValue, String actionTaken) {
        try {
            jdbc.update(
                    "INSERT INTO risk_control_log(account_id, log_date, symbol, rule_type, trigger_value, action_taken) "
                            + "VALUES (?,?,?,?,?,?)",
                    ACCOUNT_ID,
                    Date.valueOf(logDate == null ? LocalDate.now() : logDate),
                    symbol,
                    ruleType == null ? "UNKNOWN" : ruleType,
                    triggerValue,
                    actionTaken == null ? "" : actionTaken);
        } catch (Exception e) {
            log.warn("risk_control_log 写入失败: {}", e.getMessage());
        }
    }
}
