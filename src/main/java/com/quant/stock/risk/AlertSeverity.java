package com.quant.stock.risk;

/**
 * 告警分级（P0-97）：INFO &lt; WARN &lt; CRITICAL；硬风控事件用 CRITICAL。
 */
public enum AlertSeverity {
    INFO,
    WARN,
    CRITICAL
}
