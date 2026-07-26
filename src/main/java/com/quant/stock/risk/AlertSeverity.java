package com.quant.stock.risk;

/**
 * 告警分级（P0-97）：INFO &lt; WARN &lt; CRITICAL；硬风控事件用 CRITICAL。
 */
public enum AlertSeverity {
    /** 信息级，冷却较长 */
    INFO,
    /** 警告级，接近硬预算或软风控 */
    WARN,
    /** 严重级，熔断/退役/Kill 等硬事件 */
    CRITICAL
}
