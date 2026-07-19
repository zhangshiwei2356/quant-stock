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

@Data
@ConfigurationProperties(prefix = "quant")
public class QuantProperties {

    private String stockCodes = "600036,000001,300059";
    private BigDecimal maxSinglePosition = new BigDecimal("0.3");
    private BigDecimal maxTotalPosition = new BigDecimal("0.8");
    private BigDecimal feeRate = new BigDecimal("0.0003");
    private BigDecimal slipPoint = new BigDecimal("0.001");
    private BigDecimal baseAtr = new BigDecimal("0.05");
    private BigDecimal atrMinThreshold = new BigDecimal("0.001");
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
    private String marketMode = "json";
    private int mockBarDays = 30;
    private boolean dbEnabled = false;

    /** 策略过滤 */
    private boolean trendFilterEnabled = true;
    private int trendMaPeriod = 60;
    private boolean volumeFilterEnabled = true;
    private BigDecimal volumeConfirmRatio = new BigDecimal("1.2");
    private boolean adxFilterEnabled = true;
    private BigDecimal adxMin = new BigDecimal("25");
    private BigDecimal adxChopMax = new BigDecimal("20");
    private BigDecimal rsiBuyMax = new BigDecimal("60");

    /** 止损止盈 */
    private boolean stopLossEnabled = true;
    private BigDecimal atrStopMultiplier = new BigDecimal("2.0");
    private BigDecimal hardStopCapitalPct = new BigDecimal("0.02");
    private boolean trailingStopEnabled = true;
    private BigDecimal trailingAtrMultiplier = new BigDecimal("1.5");

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

    /** 开仓静默时段（分钟，相对交易时段） */
    private boolean quietOpenEnabled = true;
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
