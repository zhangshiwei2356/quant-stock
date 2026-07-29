package com.quant.stock.admin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本应用管理使用的 MySQL 表白名单（与 schema.sql 对齐）。
 */
public final class DbTableCatalog {

    /**
     * 白名单内单表元数据：名称、模块、默认排序及用途说明。
     */
    public static final class TableDef {
        private final String name;
        private final String title;
        private final String module;
        private final String orderBy;
        /** 表功能说明 */
        private final String purpose;
        /** 数据来源 */
        private final String source;
        /** 在本系统中的用途 */
        private final String usage;

        public TableDef(String name, String title, String module, String orderBy,
                        String purpose, String source, String usage) {
            this.name = name;
            this.title = title;
            this.module = module;
            this.orderBy = orderBy;
            this.purpose = purpose;
            this.source = source;
            this.usage = usage;
        }

        public String getName() {
            return name;
        }

        public String getTitle() {
            return title;
        }

        public String getModule() {
            return module;
        }

        public String getOrderBy() {
            return orderBy;
        }

        public String getPurpose() {
            return purpose;
        }

        public String getSource() {
            return source;
        }

        public String getUsage() {
            return usage;
        }
    }

    private static final Map<String, TableDef> TABLES;

    static {
        Map<String, TableDef> m = new LinkedHashMap<String, TableDef>();
        add(m, "stock_basic", "股票基本信息", "基础信息", "id DESC",
                "维护可交易/可浏览标的的基础档案（代码、简称、市场、行业、上市状态等）。",
                "本地初始化 / Mock 导入 / 后续可对接交易所或第三方证券基础信息 API。",
                "行情浏览全市场列表、盘后目标池扫描 universe、个股展示名称；status=0 的标的通常排除在扫描外。");
        add(m, "market_daily", "日线行情(兼容停用)", "行情", "id DESC",
                "历史日线物理表；应用主路径已停用读写，日线由 market_1min 内存聚合。",
                "旧 Mock/批量脚本可能仍有残留行；新写入请走 market_1min。",
                "运维浏览兼容；回测/图表不再以此表为真相源。");
        add(m, "market_minute", "5分钟行情(兼容停用)", "行情", "id DESC",
                "历史 5 分钟物理表；应用主路径已停用读写，5 分钟由 market_1min 内存聚合。",
                "旧 Mock/采集可能仍有残留行；新写入请走 market_1min。",
                "运维浏览兼容；回测/扫描不再以此表为真相源。");
        add(m, "market_1min", "1分钟行情(唯一真相源)", "行情", "id DESC",
                "唯一物理行情层：1 分钟 OHLCV；5/15/30/60/日/周/月均由此聚合。",
                "TDX 回填脚本 fetch_min1_tdx.py、收盘清算/采集落库、空库 Mock 种子。",
                "K 线查询、回测、scan-and-trade、因子计算（日线由 1 分钟聚合后写入 factor_daily）。");
        add(m, "factor_daily", "日频因子缓存", "因子", "id DESC",
                "缓存日频技术指标（MA/RSI/ATR 等）；入池粗筛可读最新行（ma5>ma20 / ma60向上 / 放量）。",
                "MockDataImporter 导入或后续因子任务写入。",
                "pool-rebuild 粗过滤加速；无行则放行该标的。");
        add(m, "factor_minute", "分钟因子缓存", "因子", "id DESC",
                "缓存分钟频技术因子（如 MA5/MA20/ATR），供分钟级策略使用。",
                "由分钟行情计算后写入。",
                "分钟扫描与分钟回测中的指标复用。");
        add(m, "trade_orders", "交易委托", "交易执行", "id DESC",
                "记录买卖委托及成交状态（含回测/实盘账户隔离）。",
                "回测引擎成交落库、或实盘下单/同步订单任务写入。",
                "订单追踪、成交复盘、账户流水对账；sync-orders 任务目标表之一。");
        add(m, "trade_positions", "持仓快照", "交易执行", "id DESC",
                "当前持仓汇总（数量、成本、止损/移动止盈参考价等）。",
                "成交后更新；亦可由持仓同步任务刷新。",
                "风控、仓位管理、实盘持仓展示与盈亏计算基础。");
        add(m, "trade_position_lots", "持仓批次", "交易执行", "id DESC",
                "按开仓批次记录持仓，用于 T+1 可卖判定等规则。",
                "开仓成交时按批次写入。",
                "卖出时判断可卖数量，落实 A 股 T+1。");
        add(m, "trade_cashflows", "账户权益日表", "交易执行", "id DESC",
                "按日记录现金、市值、总权益、回撤与当日盈亏等账户曲线点。",
                "收盘清算或回测过程按交易日汇总写入。",
                "权益曲线、回撤监控、账户级风控阈值判断。");
        add(m, "system_config", "系统动态配置", "配置与风控", "id DESC",
                "键值型动态参数，可在不改代码的情况下调整部分运行参数。",
                "schema 初始化种子 + 运维/页面后续可写。",
                "运行时读取覆盖默认配置（如阈值类参数）。");
        add(m, "risk_control_log", "风控触发日志", "配置与风控", "id DESC",
                "记录风控规则触发详情与采取的动作，便于审计。",
                "策略/风控模块在触发规则时写入。",
                "复盘风控事件、排查为何禁开仓或强制平仓。");
        add(m, "bt_backtest_record", "回测历史记录", "回测", "id DESC",
                "保存单股/组合回测结果摘要与明细 JSON，供历史列表回看。",
                "用户在页面执行回测并保存时由 BackTestHistoryStore 写入。",
                "个股/组合回测历史列表、再次打开指标与成交明细。");
        add(m, "bt_backtest_analysis", "回测分析数据", "回测", "id DESC",
                "与回测记录关联的分析事件与文字摘要（买卖点解读等）。",
                "回测分析流程生成后写入。",
                "页面展开「分析报告」时读取展示。");
        add(m, "bar_aggregate_meta", "聚合元数据(兼容)", "行情兼容", "stock_code ASC, period ASC",
                "旧版分表聚合进度元数据，标记各周期最后聚合时间。",
                "K 线聚合任务维护；新逻辑以 market_* 为主。",
                "兼容旧聚合流程，避免重复聚合；新部署可逐步弱化依赖。");
        add(m, "trade_pool_report", "目标池入选分析报告", "目标池", "id DESC",
                "盘后扫描入选时生成的完整分析快照（JSON+摘要），供行展开回看。",
                "pool-rebuild / after-market-batch-scan / 手动扫描时由 TradePoolService 写入。",
                "目标池行展开报告；通过 trade_pool.report_id 关联。");
        add(m, "trade_pool", "量化交易目标池", "目标池", "id DESC",
                "唯一目标池：盘后扫描按 TopN 自动覆盖；status=1 为活跃入池标的。",
                "BATCH_SCAN 扫描写入；亦可手工移出（status=0，不卖持仓）。",
                "scan-and-trade 只扫描本表 status=1 的代码；移出仅停用，不记历史表。");
        add(m, "sys_schedule_job", "定时任务配置", "运维中心", "id DESC",
                "动态定时任务定义（cron/固定间隔、启停、是否已实现）。",
                "schema 种子初始化；页面「运维中心 → 任务管理」修改。",
                "DynamicScheduleService 按本表注册/重载调度；改配置后立即生效。");
        add(m, "st_status_hist", "ST状态日切", "基础信息", "effective_date DESC",
                "ST/*ST 状态 as-of 历史；开仓过滤与涨跌幅规则按交易日回看。",
                "启动可从 stock_basic 快照种子；亦可 POST /api/ops/st-pit 写入。",
                "OpenFilterService.isSt(code,asOf)；无行时回退 stock_basic.is_st（有前视风险）。");
        add(m, "industry_reclass_log", "行业重分类日志", "基础信息", "effective_date DESC",
                "行业分类 as-of reclass 日志，避免用现行业回测历史。",
                "启动种子 + syncFromStockBasic；外部申万/中信源待 API。",
                "选股中性/归因用当时行业；不并金叉主路径。");
        TABLES = Collections.unmodifiableMap(m);
    }

    private static void add(Map<String, TableDef> m, String name, String title, String module, String orderBy,
                            String purpose, String source, String usage) {
        m.put(name, new TableDef(name, title, module, orderBy, purpose, source, usage));
    }

    private DbTableCatalog() {
    }

    /** 按表名（不区分大小写）获取定义；未知表返回 null */
    public static TableDef get(String name) {
        if (name == null) {
            return null;
        }
        return TABLES.get(name.trim().toLowerCase());
    }

    /** 表名是否在浏览白名单内 */
    public static boolean isAllowed(String name) {
        return get(name) != null;
    }

    /** 全部白名单表定义（插入顺序即展示顺序） */
    public static List<TableDef> all() {
        return new ArrayList<TableDef>(TABLES.values());
    }
}
