package com.quant.stock.risk;

/**
 * 预注册压力/政权情景（P0-96）。只降仓/告警/Kill，不改金叉定义。
 */
public enum StressScenario {
    ADV_CLIFF("ADV断崖", "近20日均量相对近60日均量跌破阈值 → 仓位×0.5", "SCALE_HALF"),
    LIMIT_LOCK("一字板/涨跌停死锁", "连续跌停挂卖失败达阈 → WARN（强平仍走原路径）", "ALERT"),
    CORRELATION_SPIKE("相关尖峰", "持仓平均两两相关≥阈 → WARN（见 correlation）", "ALERT"),
    DRAWDOWN_REGIME("回撤政权", "深度/持续期熔断已触发 → 禁开（既有熔断）", "KILL_OPEN"),
    LIQUIDITY_DROUGHT("流动性枯竭", "ADV 帽后买单不足1手且挂单保留 → INFO 计数", "ALERT");

    private final String title;
    private final String description;
    private final String action;

    StressScenario(String title, String description, String action) {
        this.title = title;
        this.description = description;
        this.action = action;
    }

    /** 展示标题 */
    public String getTitle() {
        return title;
    }

    /** 情景说明 */
    public String getDescription() {
        return description;
    }

    /** 预设动作：SCALE_HALF / ALERT / KILL_OPEN 等 */
    public String getAction() {
        return action;
    }
}
