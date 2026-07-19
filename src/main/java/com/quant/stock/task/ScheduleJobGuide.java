package com.quant.stock.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预置定时任务详细介绍（页面行展开用）。
 */
public final class ScheduleJobGuide {

    public static final class Detail {
        private final String purpose;
        private final String scope;
        private final String triggerHint;
        private final String writes;
        private final String notes;

        public Detail(String purpose, String scope, String triggerHint, String writes, String notes) {
            this.purpose = purpose;
            this.scope = scope;
            this.triggerHint = triggerHint;
            this.writes = writes;
            this.notes = notes;
        }

        public String getPurpose() {
            return purpose;
        }

        public String getScope() {
            return scope;
        }

        public String getTriggerHint() {
            return triggerHint;
        }

        public String getWrites() {
            return writes;
        }

        public String getNotes() {
            return notes;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("purpose", purpose);
            m.put("scope", scope);
            m.put("triggerHint", triggerHint);
            m.put("writes", writes);
            m.put("notes", notes);
            return m;
        }
    }

    private static final Map<String, Detail> GUIDE;

    static {
        Map<String, Detail> m = new LinkedHashMap<String, Detail>();
        m.put("market-collect", new Detail(
                "按全市场股票列表拉取/刷新本地 K 线（日线、分钟），保证后续扫描与回测有行情可用。",
                "遍历 stock_basic / 配置 universe 中的全部标的。",
                "默认 FIXED_RATE 约 30 秒；建议仅在交易时段开启。",
                "写入或更新 market_daily / market_minute（视数据源实现而定）。",
                "当前为占位骨架：真实行情 API 未接入时走本地 mock/已有库数据回退；页面标「未实现」。"
        ));
        m.put("scan-and-trade", new Detail(
                "实盘分钟级扫描：对唯一目标池标的计算信号，并按策略规则模拟下单/调仓。",
                "仅扫描 trade_pool 中 status=1 的标的；目标池为空则跳过。",
                "默认工作日 9–11、13–15 点每分钟一次（CRON）；非交易日（周末/内置节假日）风控拒单。",
                "写入 trade_orders；更新 trade_positions / trade_position_lots；现金写入 system_config(sim.cash)。",
                "已实现（本地模拟账本，重启可恢复）。需先有盘后扫描写入的目标池标的，并开启总闸与本任务。真券商下单见应用待办。"
        ));
        m.put("sync-orders", new Detail(
                "同步委托/成交状态：将本地未完结订单与券商柜台状态对齐。",
                "针对本地内存/库中 SUBMITTED 等未完结订单。",
                "默认 FIXED_RATE 约 10 秒。",
                "理想情况更新 trade_orders 状态；当前仅本地桩将 SUBMITTED→FILLED。",
                "未实现：待接入券商委托查询/成交回报 API；开启后不会真正对账。"
        ));
        m.put("position-pnl-sync", new Detail(
                "持仓盈亏同步：用最新价估算持仓市值与浮动概况，便于监控。",
                "本地 trade_positions / 网关持仓快照中的持仓标的。",
                "默认交易时段每分钟一次（CRON）。",
                "本地估值与账户概览；券商对账后可回写成本/可用数量。",
                "部分实现：本地持仓+最新价估算；真实柜台盈亏/可用需成交回报 API。"
        ));
        m.put("settle-after-close", new Detail(
                "收盘后清算：账户日结、权益落库，并做 K 线聚合等收盘批处理。",
                "当日账户与持仓；池内∪持仓标的的行情聚合。",
                "默认工作日 15:30（CRON）。",
                "写入 trade_cashflows（权益日表）、更新 system_config(sim.cash)；聚合依赖本地/已有行情。",
                "本地日结已实现；真实行情增量拉取仍待外部行情 API（见应用待办）。"
        ));
        m.put("pool-rebuild", new Detail(
                "全市场扫描：按策略条件筛选可入选标的，覆盖唯一目标池，并生成分析报告落库。",
                "全市场 universe（stock_basic，粗过滤 ST）；按分数取 TopN 后整池替换。",
                "默认工作日 15:10（CRON）；亦可在页面点「扫描更新」手动触发同类逻辑。",
                "写入/覆盖 trade_pool，并写入 trade_pool_report；旧活跃行先停用再按本批入选。",
                "已实现。扫描后覆盖唯一目标池；与 after-market-batch-scan 启用其一即可。"
        ));
        m.put("after-market-batch-scan", new Detail(
                "盘后再次扫描覆盖唯一目标池：与 pool-rebuild 同类，适合收盘后统一重算一遍入选名单。",
                "全市场 universe；整池替换 trade_pool（非增量追加）。",
                "默认工作日 16:00（CRON）。",
                "覆盖 trade_pool，并写入对应 trade_pool_report。",
                "已实现。扫描后覆盖唯一目标池；与 pool-rebuild 启用其一即可。"
        ));
        m.put("data-validate", new Detail(
                "数据质量校验：检查各标的日线/分钟线是否为空或明显滞后，并输出告警日志。",
                "全市场 universe 的 market_daily / market_minute。",
                "默认工作日 17:00（CRON）。",
                "只读检查，默认不改业务表；问题写入日志。",
                "未实现外部对账：当前仅本地滞后/空数据检查；与外部行情 OHLC 抽样对账待 API。"
        ));
        GUIDE = Collections.unmodifiableMap(m);
    }

    private ScheduleJobGuide() {
    }

    public static Detail get(String jobCode) {
        if (jobCode == null) {
            return null;
        }
        return GUIDE.get(jobCode.trim());
    }

    public static Map<String, Object> toViewMap(String jobCode) {
        Detail d = get(jobCode);
        if (d == null) {
            Map<String, Object> fallback = new LinkedHashMap<String, Object>();
            fallback.put("purpose", "自定义或未收录的任务，请结合备注与代码实现查看。");
            fallback.put("scope", "—");
            fallback.put("triggerHint", "以库表 trigger_type / cron / interval 为准。");
            fallback.put("writes", "—");
            fallback.put("notes", "可在备注字段补充说明。");
            return fallback;
        }
        return d.toMap();
    }
}
