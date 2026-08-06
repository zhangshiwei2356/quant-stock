package com.quant.stock.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预置定时任务详细介绍（页面行展开用）。
 */
public final class ScheduleJobGuide {

    /** 运维页展示用：单条预置任务的说明块。 */
    public static final class Detail {
        /** 任务目的 */
        private final String purpose;
        /** 作用范围（标的/账户等） */
        private final String scope;
        /** 触发时机提示 */
        private final String triggerHint;
        /** 会写入的数据 */
        private final String writes;
        /** 补充说明与边界 */
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

        /** 转为 JSON 友好的键值结构。 */
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
                "遍历 stock_basic / 配置 universe 中的全部标的；若宽睿 MDS live 则走查询/订阅落库。",
                "默认 FIXED_RATE 约 30 秒；建议仅在交易时段开启。",
                "写入或更新 market_1min（池内分钟真相源）；MDS 写入 data_source=MDS。全市场日线请用 fetch_daily_tdx→market_daily。",
                "默认关宽睿时走本地 mock/已有 market_1min 回退；开启需 -Pkuangrui + quant.kuangrui.mds.enabled。"
        ));
        m.put("scan-and-trade", new Detail(
                "实盘分钟级扫描：对唯一目标池标的计算信号，并按策略规则模拟下单/调仓。",
                "仅扫描 trade_pool 中 status=1 的标的；目标池为空则跳过。",
                "默认工作日 9–11、13–15 点每分钟一次（CRON）；非交易日（周末/内置节假日）风控拒单。",
                "写入 trade_orders；更新 trade_positions / trade_position_lots；现金写入 system_config(sim.cash)。",
                "已实现（本地模拟账本，重启可恢复）。需先有盘后扫描写入的目标池标的，并开启总闸与本任务。真券商下单见「能力与待办」。"
        ));
        m.put("sync-orders", new Detail(
                "同步委托/成交状态：将本地已报订单推进为成交，并落本地仓位与现金。",
                "针对本地内存中 SUBMITTED 未完结订单（trade-mode=sdk）。",
                "默认 FIXED_RATE 约 10 秒；亦可运维「执行一次」。",
                "默认本地桩 SUBMITTED→FILLED；若宽睿 OES order-enabled live，则按柜台回报/查询推进 FILLED（不假推进）。",
                "本地桩 + M2 对账日志 + M3 可选真实报撤回报（默认关）。"
        ));
        m.put("position-pnl-sync", new Detail(
                "持仓盈亏同步：用本地成本与最新价估算市值与浮动盈亏，便于监控。",
                "策略账本持仓（成本/数量）+ 最新价；可选 OES 资金/持仓。",
                "默认交易时段每分钟一次（CRON）。",
                "日志输出市值、浮盈及合计；不写库。OES live 时附加柜台对账日志。",
                "本地已实现；OES 只读对账为可选旁路（M2，默认关）。"
        ));
        m.put("settle-after-close", new Detail(
                "收盘后清算：账户日结、权益落库，并做 K 线聚合等收盘批处理。",
                "当日账户与持仓；池内∪持仓标的的行情聚合。",
                "默认工作日 15:30（CRON）。",
                "写入 trade_cashflows（权益日表）、更新 system_config(sim.cash)；聚合依赖本地/已有行情。",
                "本地日结已实现；真实行情增量拉取仍待外部行情 API（见「能力与待办」）。"
                        + " K 线：刷新/落库 market_1min，更大周期查询时聚合。"
        ));
        m.put("pool-rebuild", new Detail(
                "全市场扫描：按策略条件筛选可入选标的，覆盖唯一目标池，并生成分析报告落库。",
                "全市场 universe（stock_basic，粗过滤 ST）；可选预刷 factor_daily；按分数取 TopN 后整池替换。",
                "默认工作日 15:10（CRON）；亦可在「当前池 / 扫描历史」点「扫描更新」手动触发同类逻辑。",
                "写入/覆盖 trade_pool，并写入 trade_pool_report；返回 minuteBackfillHint 提示池内补分钟。",
                "已实现。默认 quant.pool-rebuild-refresh-factors=true；入池后请执行 fetch_min1_tdx.py --from-pool。"
        ));
        m.put("after-market-batch-scan", new Detail(
                "盘后再次扫描覆盖唯一目标池：与 pool-rebuild 同类，适合收盘后统一重算一遍入选名单。",
                "全市场 universe；整池替换 trade_pool（非增量追加）。",
                "默认工作日 16:00（CRON）。",
                "覆盖 trade_pool，并写入对应 trade_pool_report。",
                "已实现。扫描后覆盖唯一目标池；与 pool-rebuild 启用其一即可。"
        ));
        m.put("data-validate", new Detail(
                "分层校验行情：全市场查日线，目标池查分钟。",
                "universe → market_daily；trade_pool 活跃 → market_1min（非池不因缺分钟告警）。",
                "默认工作日 17:00（CRON）。",
                "只读检查，默认不改业务表；问题写入日志；可顺带触发分钟行情自洽检查。",
                "已实现本地分层检查；与外部行情 OHLC 抽样对账待 API。"
        ));
        m.put("factor-daily-rebuild", new Detail(
                "由日线重算 factor_daily，供入池粗筛（ma5>ma20 / ma60向上 / 放量）。",
                "有日线的全市场标的；亦可 POST /api/ops/factor-daily/rebuild。",
                "默认工作日 15:00（CRON，种子关）；建议在 pool-rebuild 前。",
                "覆盖写入 factor_daily。",
                "已实现。pool-rebuild 默认也会预刷新（可关 quant.pool-rebuild-refresh-factors）。"
        ));
        m.put("day-collect", new Detail(
                "全市场日线补齐：无数据补近1年；已齐（最新日线≤3日历日）直接跳过；否则增量补缺口。默认 4 线程并行拉 TDX。",
                "stock_basic status=1 → market_daily（TDX，adj=NONE）；--from-basic 默认先同步全市场列表约5000+。",
                "默认工作日 15:30（CRON，种子关）；运维「执行一次」可手动触发。",
                "依赖本机 Python + pytdx/pymysql；需 quant.tdx-script.enabled=true。",
                "已实现。推荐流水线第 1 步；亦可 POST /api/ops/tdx-script/backfill-daily。"
        ));
        m.put("pool-minute-backfill", new Detail(
                "目标池分钟补齐：尽量拉满 TDX 节点深度（约 90 交易日）并 upsert 到最近。",
                "trade_pool status=1 → market_1min。",
                "默认工作日 15:20（CRON，种子关）；建议在 pool-rebuild 之后手动执行。",
                "依赖本机 Python + pytdx；需 quant.tdx-script.enabled=true。",
                "已实现。推荐流水线第 3 步；亦可 POST /api/ops/tdx-script/backfill-min1。"
        ));
        GUIDE = Collections.unmodifiableMap(m);
    }

    private ScheduleJobGuide() {
    }

    /** 按任务编码取预置说明；未收录返回 null。 */
    public static Detail get(String jobCode) {
        if (jobCode == null) {
            return null;
        }
        return GUIDE.get(jobCode.trim());
    }

    /** 转为前端/接口用的说明 Map；未知编码返回通用占位文案。 */
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
