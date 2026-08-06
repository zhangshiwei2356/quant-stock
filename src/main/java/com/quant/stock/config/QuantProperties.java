package com.quant.stock.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 应用级量化配置绑定（前缀 {@code quant}）。
 * <p>
 * 职责：集中承载策略过滤、止损/仓位、账户熔断、撮合成本、目标池粗筛、API 安全与调度总闸等可调参数，
 * 供引擎、风控、数据层与 Web 层注入使用。
 * </p>
 * <p>
 * 关键约束：默认值面向本地演示；生产环境需显式配置库连接、API Key、限流与风控阈值。
 * 调度 cron 与单任务开关以 MySQL {@code sys_schedule_job} 为准，{@link Schedule} 仅保留总闸与兼容字段。
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "quant")
public class QuantProperties {

    /** 默认关注标的，逗号分隔代码列表 */
    private String stockCodes = "600036,000001,300059,601318,000858,600519,000568,002415,600276,601166";
    /** 单票最大仓位占权益比例 */
    private BigDecimal maxSinglePosition = new BigDecimal("0.3");
    /** 组合总仓位占权益硬顶 */
    private BigDecimal maxTotalPosition = new BigDecimal("0.8");
    /** 单边佣金/手续费率（买卖共用基准） */
    private BigDecimal feeRate = new BigDecimal("0.0003");
    /** 基础滑点（与分级滑点并存时的简化项） */
    private BigDecimal slipPoint = new BigDecimal("0.001");
    /** ATR 计算基准比例（策略内部缩放） */
    private BigDecimal baseAtr = new BigDecimal("0.05");
    /** ATR 低于此阈值时视为波动过小 */
    private BigDecimal atrMinThreshold = new BigDecimal("0.001");
    /** 批量扫描线程池大小（与 {@link ThreadPoolConfig} 一致） */
    private int batchPoolSize = 10;
    /** 交易候选池最大容量（入池扫描截断） */
    private int tradePoolMax = 30;
    /**
     * 目标池多因子入选最低分（0~100）。
     * 趋势+动量+波动+流动性综合分低于此值不入池。
     */
    private BigDecimal poolScoreMin = new BigDecimal("45");
    /** 粗筛：上市满 N 天（排除次新）；0 表示不启用 */
    private int poolMinListDays = 60;
    /**
     * 粗筛：近 20 日均成交额下限（元）；0 表示不启用。
     * 生产建议 50000000（5000 万）；本地 mock 默认关闭以免池被滤空。
     */
    private long poolMinAvgAmount20 = 0L;
    /** 行情数据源模式：如 {@code json}、库表等 */
    private String marketMode = "json";
    /**
     * 日线读取策略（仅 db 模式）：
     * {@code auto}=优先 {@code market_daily}，空则 {@code market_1min} 聚日；
     * {@code table}=仅日线表；{@code aggregate}=仅分钟聚日。
     */
    private String daySource = "auto";
    /**
     * pool-rebuild 前是否先按日线重算 {@code factor_daily}（默认 false 加速入池；
     * 需要更准粗筛时可开，或单独跑 {@code factor-daily-rebuild}）。
     */
    private boolean poolRebuildRefreshFactors = false;
    /**
     * pool-rebuild 扫池是否跑完整日线回测（默认 false：只算指标/信号/动量，入池打分足够；
     * true 时每只全量 {@code BackTestEngine}，全市场很慢）。
     */
    private boolean poolRebuildFullBacktest = false;
    /**
     * pool-rebuild 成功后是否异步调用 TDX 分钟回填脚本（需 {@link TdxScript#enabled}=true）。
     * 默认 false，避免未装 Python/pytdx 时拖垮扫池。
     */
    private boolean poolRebuildBackfillMinute = false;
    /** 通达信 Python 灌数脚本（日线/池内分钟）；默认关闭，运维显式开启。 */
    private TdxScript tdxScript = new TdxScript();
    /**
     * 当前激活策略 id（单活可切换），对应 {@link com.quant.stock.strategy.BaseStrategy#name()}。
     * 默认 {@code maCross}；可选已注册策略如 {@code holdNothing}。
     */
    private String activeStrategy = "maCross";
    /** mock/JSON 模式下生成的 K 线天数 */
    private int mockBarDays = 30;
    /** 是否启用 MySQL 与 MyBatis（false 时走 classpath 模拟数据） */
    private boolean dbEnabled = false;

    /** 策略过滤 */
    private boolean trendFilterEnabled = true;
    /** 趋势均线周期（日） */
    private int trendMaPeriod = 60;
    private boolean volumeFilterEnabled = true;
    /** 放量确认：当日量 / 均量 下限 */
    private BigDecimal volumeConfirmRatio = new BigDecimal("1.2");
    private boolean adxFilterEnabled = true;
    /** ADX 趋势强度下限 */
    private BigDecimal adxMin = new BigDecimal("25");
    /** ADX 低于此值视为震荡，配合过滤 */
    private BigDecimal adxChopMax = new BigDecimal("20");
    /** 买入时 RSI 上限 */
    private BigDecimal rsiBuyMax = new BigDecimal("60");

    /** 止损止盈 */
    private boolean stopLossEnabled = true;
    /** ATR 倍数止损距离 */
    private BigDecimal atrStopMultiplier = new BigDecimal("2.0");
    /** 硬止损：相对权益的最大亏损比例 */
    private BigDecimal hardStopCapitalPct = new BigDecimal("0.02");
    private boolean trailingStopEnabled = true;
    /** 移动止损 ATR 倍数 */
    private BigDecimal trailingAtrMultiplier = new BigDecimal("1.5");
    /**
     * 最大持仓交易日（开仓日后计；P0-114）。到期挂时间止损清仓。
     * 默认 0=关闭；生产可设 40～60。
     */
    private int maxHoldTradingDays = 0;

    /** 金字塔仓位 50/30/20 */
    private boolean pyramidEnabled = true;
    private BigDecimal pyramidFirst = new BigDecimal("0.50");
    private BigDecimal pyramidSecond = new BigDecimal("0.30");
    private BigDecimal pyramidThird = new BigDecimal("0.20");
    private BigDecimal pyramidAddPct = new BigDecimal("0.01");

    /** 账户熔断 */
    private BigDecimal dailyLossLimitPct = new BigDecimal("0.03");
    private int consecutiveLossLimit = 5;
    private BigDecimal drawdownReducePct = new BigDecimal("0.15");
    private BigDecimal drawdownHaltPct = new BigDecimal("0.25");
    /**
     * 回撤持续期降仓：权益低于峰值满 N 个交易日 → 仓位×0.5（P0-122）。
     * 0=关闭。与深度降仓（drawdownReducePct）并行，先触达者生效。
     */
    private int drawdownDurationReduceDays = 10;
    /**
     * 回撤持续期熔断：低于峰值满 N 个交易日 → 熔断禁开（P0-122）。
     * 0=关闭。可在未触及深度 halt 时触发「阴跌」熔断。
     */
    private int drawdownDurationHaltDays = 30;
    /**
     * 持续期熔断时是否自动退役策略（P0-92）；退役后禁新开，需冷却满后再 resume。
     */
    private boolean autoRetireOnDurationHalt = true;
    /** 退役冷却交易日；满后方可 POST resume。0=禁止自动 resume（仅手动 force） */
    private int retirementCooldownTradingDays = 20;

    /** 组合相关监控：收益回看交易日数（P0-105） */
    private int correlationLookbackDays = 60;
    /** 平均两两相关告警阈值 */
    private BigDecimal correlationWarnThreshold = new BigDecimal("0.75");

    /** 告警冷却：WARN 分钟（P0-97） */
    private int alertCooldownWarnMinutes = 240;
    /** 告警冷却：CRITICAL 分钟 */
    private int alertCooldownCriticalMinutes = 60;
    /** 软预算：总仓占比告警线（相对权益；硬顶仍为 maxTotalPosition） */
    private BigDecimal softTotalPositionPct = new BigDecimal("0.70");
    /** 软预算：单票占比告警线 */
    private BigDecimal softSinglePositionPct = new BigDecimal("0.25");

    /** 限价保护：成交价夹紧到涨跌停（P0-94）；无五档时仅此+ADV 帽 */
    private boolean limitPriceProtectEnabled = true;

    /**
     * 回测部成比例（P0-95）。1=满额（默认）；&lt;1 时本 bar 只成交该比例整手，残量保留挂单。
     */
    private BigDecimal backtestFillRatio = BigDecimal.ONE;

    /** 压力情景总开关（P0-96） */
    private boolean stressScenarioEnabled = true;
    /**
     * ADV 断崖：近20均量 / 近60均量 &lt; 此值 → 仓位×0.5。默认 0.40；≤0 关闭断崖判定。
     */
    private BigDecimal stressAdvCliffRatio = new BigDecimal("0.40");

    /** 信号漂移监控（P0-90） */
    private boolean signalDriftEnabled = true;
    /** 漂移统计回看轮数 */
    private int driftLookbackRounds = 20;
    /** 漂移告警最低胜率 */
    private BigDecimal driftMinWinRate = new BigDecimal("0.35");
    /** IC 漂移回看交易日 */
    private int driftIcLookbackDays = 60;
    /** 漂移 IC 下限 */
    private BigDecimal driftMinIc = new BigDecimal("0.02");
    /** 连续多少轮确认后视为漂移成立 */
    private int driftConfirmRounds = 3;
    /** 漂移确认后是否自动退役；默认 false（只 CRITICAL 告警） */
    private boolean autoRetireOnSignalDrift = false;

    /** 多源对账闸（P0-107）：日线 vs 分钟聚合 */
    private boolean dataReconcileGateEnabled = true;
    /** 对账分歧时是否阻断交易（默认仅告警） */
    private boolean dataReconcileBlockOnDiverge = false;
    /** 对账允许的最大收盘价相对偏差 */
    private BigDecimal dataReconcileMaxCloseDiffPct = new BigDecimal("0.02");
    /** 对账抽样天数 */
    private int dataReconcileSampleDays = 5;

    /**
     * 容量基准权益（P0-112）：权益超过此值时按比例收紧 ADV 参与率（扩容降频）。
     * 默认与演示初始资金同量级；≤0 关闭缩放。
     */
    private BigDecimal capacityAumBase = new BigDecimal("100000");
    /**
     * POV：单笔不超过当根成交量 × 此比例；≤0 关闭（仅 ADV 帽）。
     */
    private BigDecimal povMaxBarVolumePct = new BigDecimal("0.10");

    /** 结构突变（P0-120） */
    private boolean structuralBreakEnabled = true;
    /** 结构突变检测窗口长度（bar） */
    private int structuralBreakWindow = 20;
    /** 结构突变统计量阈值 */
    private BigDecimal structuralBreakThreshold = new BigDecimal("2.0");
    /** 连续满足条件的 bar 数才确认突变 */
    private int structuralBreakConfirmBars = 2;

    /**
     * ST 开仓过滤（P0-101）：as-of 为 ST 则禁开；涨跌幅仍按 ST 规则。
     * 默认 true；无日切表时回退 stock_basic 现状态。
     */
    private boolean stOpenFilterEnabled = true;

    /** 换手门禁（P0-104）：日成交额/权益 */
    private boolean turnoverGuardEnabled = true;
    private BigDecimal turnoverSoftPct = new BigDecimal("0.50");
    private BigDecimal turnoverHardPct = new BigDecimal("1.00");

    /** IC 衰减监控（P0-125） */
    private boolean icDecayEnabled = true;
    /** IC 衰减回看长度 */
    private int icDecayLookback = 40;
    /** 半衰期低于此交易日数则告警降仓；0=不按半衰期判 */
    private int icDecayMinHalfLifeBars = 5;
    /** IC 信息比率下限 */
    private BigDecimal icDecayMinIr = new BigDecimal("0.10");

    /**
     * 实验种子（P0-93）：写入配置指纹，便于对照实验；不影响撮合随机性（引擎无此随机）。
     */
    private String experimentSeed = "";

    /** 开仓静默：开盘时段禁新开 */
    private boolean quietOpenEnabled = true;
    /** 开仓静默：收盘时段禁新开 */
    private boolean quietCloseEnabled = true;

    /** 流动性/市值门槛（模拟） */
    private long minAvgVolume20 = 5000000L;
    private BigDecimal minMarketCapYi = new BigDecimal("50");
    /** 是否启用市值过滤；json/mock 演示可关 */
    private boolean marketCapFilterEnabled = true;
    /**
     * 流通股本（亿股），按代码覆盖默认启发式。
     * 例：quant.float-shares-yi.600036: 252
     */
    private Map<String, BigDecimal> floatSharesYi = new HashMap<String, BigDecimal>();

    /**
     * 交易模式：sim=模拟即时成交；sdk=下单 SUBMITTED，由 sync 推进（桩实现）。
     */
    private String tradeMode = "sim";

    /** 成本：印花税（卖出）、分级滑点 */
    private BigDecimal stampTaxRate = new BigDecimal("0.001");
    private BigDecimal slipLarge = new BigDecimal("0.0005");
    private BigDecimal slipMid = new BigDecimal("0.002");
    private BigDecimal slipSmall = new BigDecimal("0.005");
    private long volLargeThreshold = 20000000L;
    private long volMidThreshold = 5000000L;
    private BigDecimal impactCoeff = new BigDecimal("0.1");
    /**
     * 单笔最大参与率（相对近 20 日均量 ADV）。默认 0.10=10%；≤0 关闭。
     * 买入挂单与死叉卖出受此硬顶；止损/熔断卖出不受限。
     */
    private BigDecimal maxParticipationAdv = new BigDecimal("0.10");

    /** 下一根开盘撮合（消除未来函数） */
    private boolean nextBarOpenFill = true;

    /**
     * API 访问密钥；非空则要求请求头 X-API-Key 匹配。
     * 本地演示默认留空=不鉴权；公网/共享环境务必配置。
     */
    private String apiKey = "";

    /** 重接口每 IP 每分钟上限（回测/组合/批量） */
    private int rateLimitPerMinute = 30;

    /** 回测历史目录（相对工作目录或绝对路径） */
    private String historyDir = "data/backtest";

    /**
     * 定时任务总闸。各任务启停与 cron 以 MySQL {@code sys_schedule_job} 为准（页面可改）。
     * enabled=false 时不注册任何触发器，库表仍可编辑。
     */
    private Schedule schedule = new Schedule();

    /**
     * 旁路会话引擎（MIN_1 三分支）窗口与撮合开关；不影响经典金叉引擎。
     */
    private Session session = new Session();

    /**
     * 宽睿 Quant360 对接开关（前缀 {@code quant.kuangrui}）。
     * 默认全关；主路径仍为 {@code sim}+{@code db}。真实 MDS 实现仅 {@code -Pkuangrui} 编译进包。
     */
    private Kuangrui kuangrui = new Kuangrui();

    /**
     * 宽睿对接配置。
     */
    @Data
    public static class Kuangrui {
        /** 总闸；false 时不装配真实 MDS/OES 客户端 */
        private boolean enabled = false;
        /** 外部配置目录（含 mds/oes json；默认可指向 gitignore 的 local） */
        private String configDir = "config/kuangrui/local";
        private Mds mds = new Mds();
        private Oes oes = new Oes();

        @Data
        public static class Mds {
            /** MDS L1 摄入；需同时 {@code kuangrui.enabled=true} */
            private boolean enabled = false;
            /** 相对 {@link Kuangrui#configDir} 的 MDS JSON 文件名 */
            private String configFile = "mds_api_config.json";
            /**
             * 订阅/拉取标的，逗号分隔；空则用目标池 universe，再空则用 {@code quant.stock-codes}。
             */
            private String subscribeCodes = "";
            /** 可选登录加密类型（对齐 {@code OesLogonEncryptType#value()}）；null=跟 Demo 不显式设置 */
            private Integer encryptType;
            /** market-collect 在 MDS live 时是否优先 pull；默认 true */
            private boolean collectPull = true;
            /** market-collect 是否在订阅模式下只 flush；默认 true */
            private boolean collectFlush = true;
        }

        @Data
        public static class Oes {
            /** OES 对接（M2/M3，尚未实现业务） */
            private boolean enabled = false;
            /** 报单总闸；默认 false */
            private boolean orderEnabled = false;
            private String configFile = "oes_api_config.json";
        }
    }

    /**
     * 会话引擎配置（前缀 {@code quant.session}）。
     */
    @Data
    public static class Session {
        /** OPEN 起始（含），HH:mm */
        private String openStart = "09:30";
        /** OPEN 结束（不含） */
        private String openEnd = "10:00";
        private String midStart = "10:00";
        private String midEnd = "14:30";
        private String closeStart = "14:30";
        private String closeEnd = "15:00";
        /**
         * 是否处理策略 {@code pollIntents} 并撮合。默认 true；脚手架不下单意图时仍为 0 成交。
         */
        private boolean matchingEnabled = true;
        /**
         * 成交模式：
         * {@code AUTO}=跟随 {@code quant.next-bar-open-fill}（true→NEXT_EFFECTIVE，false→BAR_CLOSE）；
         * {@code NEXT_EFFECTIVE}=挂单，次日且分钟≥09:45 按开盘价撮合（对齐经典）；
         * {@code BAR_CLOSE}=当根分钟收盘价即时成交。
         */
        private String fillMode = "AUTO";
        /**
         * 纸面 {@code scan-and-trade}：激活策略实现 {@code SessionStrategy} 时是否走会话钩子。
         * 默认 true；金叉等非会话策略不受影响。
         */
        private boolean paperEnabled = true;
    }

    /**
     * 通达信公开节点灌数脚本配置（调用本机 Python + scripts/fetch_*_tdx.py）。
     */
    @Data
    public static class TdxScript {
        /** 总开关；false 时运维 API / 定时 / pool 后回填均拒绝执行（yml 默认 true） */
        private boolean enabled = true;
        /** Python 可执行文件，如 python / py / python3 */
        private String python = "python";
        /**
         * 工作目录（含 scripts/ 的仓库根）。空则用 {@code user.dir}。
         */
        private String workingDir = "";
        private String min1Script = "scripts/fetch_min1_tdx.py";
        private String dailyScript = "scripts/fetch_daily_tdx.py";
        /** 单次脚本超时秒数 */
        private int timeoutSeconds = 3600;
    }

    /**
     * 定时任务相关 YAML 片段（总闸与废弃布尔项）。
     * <p>
     * 关键约束：实际 cron 与启停以库表 {@code sys_schedule_job} 为准；{@code enabled=false} 时不注册任何 Spring 触发器。
     * </p>
     */
    @Data
    public static class Schedule {
        /** 总闸；false 时 DynamicScheduleService 不注册触发器 */
        private boolean enabled = true;
        /** @deprecated 已废弃，改用库表 sys_schedule_job */
        private boolean scanAndTrade = true;
        /** @deprecated 已废弃，改用库表 sys_schedule_job */
        private boolean syncOrders = true;
        /** @deprecated 已废弃，改用库表 sys_schedule_job */
        private boolean settleAfterClose = true;
        /** @deprecated 已废弃，改用库表 sys_schedule_job */
        private boolean afterMarketBatchScan = true;
    }

    /**
     * 将 {@link #stockCodes} 解析为去空白、去空的代码列表。
     *
     * @return 不可变语义上的新列表；配置为空或仅空白时返回空列表（非 null）
     */
    public List<String> stockCodeList() {
        if (stockCodes == null || stockCodes.trim().isEmpty()) {
            return new ArrayList<String>();
        }
        return Arrays.stream(stockCodes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
