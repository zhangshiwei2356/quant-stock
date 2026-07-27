package com.quant.stock.admin;

import com.quant.stock.config.QuantProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行参数只读视图：QuantProperties 生效值 + system_config 表键值。
 */
@Service
public class SystemParamsService {

    /** system_config 常见键的中文名（库表 description 为空时兜底） */
    private static final Map<String, String> SYSTEM_CONFIG_LABELS;

    static {
        Map<String, String> m = new HashMap<String, String>();
        m.put("sim.cash", "模拟账本现金余额");
        m.put("sim.risk.state", "模拟账户风控快照（峰值/熔断/水下日）");
        m.put("sim.retirement", "策略退役状态快照");
        m.put("sim.books.meta", "模拟挂买/挂卖与金字塔元数据");
        SYSTEM_CONFIG_LABELS = Collections.unmodifiableMap(m);
    }

    private final QuantProperties props;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public SystemParamsService(QuantProperties props, ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.props = props;
        this.jdbcProvider = jdbcProvider;
    }

    /** 只读展示当前生效的 QuantProperties 分组与 system_config 键值 */
    public Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("dbEnabled", props.isDbEnabled());
        m.put("hint", "只读展示当前生效配置（中文说明 + 配置项键名）；修改请改 application.yml / 环境变量或 system_config（部分键）。");
        m.put("groups", buildGroups());
        m.put("systemConfig", loadSystemConfig());
        return m;
    }

    private List<Map<String, Object>> buildGroups() {
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        groups.add(group("基础",
                kv("tradeMode", "交易模式", props.getTradeMode(), "sim=本地模拟；sdk=券商 SDK 桩"),
                kv("dbEnabled", "启用 MySQL", props.isDbEnabled(), "false 时行情/账本等不落库"),
                kv("marketMode", "行情模式", props.getMarketMode(), "db / json / sdk"),
                kv("activeStrategy", "当前策略", props.getActiveStrategy(),
                        "单活策略 id；默认 maCross（金叉）；holdNothing=永不交易占位"),
                kv("stockCodes", "默认股票池", props.getStockCodes(), "演示用代码列表"),
                kv("feeRate", "佣金费率", props.getFeeRate(), "双边佣金比例"),
                kv("slipPoint", "基础滑点", props.getSlipPoint(), "回测撮合滑点"),
                kv("scheduleEnabled", "定时任务总闸", props.getSchedule() != null && props.getSchedule().isEnabled(),
                        "false 时不注册触发器；各任务仍以库表为准"),
                kv("historyDir", "回测历史目录", props.getHistoryDir(), "本地 JSON 历史路径（兼容）"),
                kv("rateLimitPerMinute", "接口限流(次/分)", props.getRateLimitPerMinute(), "回测/组合/批量；≤0 关闭")));
        groups.add(group("仓位",
                kv("maxSinglePosition", "单票最大仓位", props.getMaxSinglePosition(), "相对总权益比例"),
                kv("maxTotalPosition", "总仓位上限", props.getMaxTotalPosition(), "相对总权益比例"),
                kv("pyramidEnabled", "金字塔加仓", props.isPyramidEnabled(), null),
                kv("pyramidFirst/Second/Third", "金字塔三档比例",
                        props.getPyramidFirst() + " / " + props.getPyramidSecond() + " / " + props.getPyramidThird(),
                        "首仓 / 二加 / 三加"),
                kv("pyramidAddPct", "加仓触发涨幅", props.getPyramidAddPct(), "相对成本上涨该比例可加仓")));
        groups.add(group("目标池",
                kv("tradePoolMax", "目标池上限", props.getTradePoolMax(), "盘后扫描入选 TopN"),
                kv("poolScoreMin", "入池最低综合分", props.getPoolScoreMin(), "多因子打分门槛"),
                kv("poolMinListDays", "最短上市天数", props.getPoolMinListDays(), null),
                kv("poolMinAvgAmount20", "20日均成交额下限(元)", props.getPoolMinAvgAmount20(), "0=关闭过滤")));
        groups.add(group("风控",
                kv("dailyLossLimitPct", "单日亏损上限", props.getDailyLossLimitPct(), "相对权益"),
                kv("consecutiveLossLimit", "连亏笔数上限", props.getConsecutiveLossLimit(), null),
                kv("drawdownReducePct", "回撤降仓阈值", props.getDrawdownReducePct(), "触及后缩仓"),
                kv("drawdownHaltPct", "回撤熔断阈值", props.getDrawdownHaltPct(), "触及后停止开仓"),
                kv("drawdownDurationReduceDays", "回撤持续期降仓(日)", props.getDrawdownDurationReduceDays(),
                        "低于峰值满 N 交易日仓位×0.5；0=关"),
                kv("drawdownDurationHaltDays", "回撤持续期熔断(日)", props.getDrawdownDurationHaltDays(),
                        "低于峰值满 M 交易日熔断；0=关"),
                kv("autoRetireOnDurationHalt", "持续期熔断自动退役", props.isAutoRetireOnDurationHalt(), null),
                kv("retirementCooldownTradingDays", "退役冷却交易日", props.getRetirementCooldownTradingDays(),
                        "满后方可 resume"),
                kv("correlationLookbackDays", "组合相关回看天数", props.getCorrelationLookbackDays(), null),
                kv("correlationWarnThreshold", "组合相关告警阈值", props.getCorrelationWarnThreshold(),
                        "平均两两相关≥此值告警"),
                kv("alertCooldownWarnMinutes", "WARN告警冷却(分)", props.getAlertCooldownWarnMinutes(),
                        "同类 WARN 冷却窗内去重"),
                kv("alertCooldownCriticalMinutes", "CRITICAL告警冷却(分)", props.getAlertCooldownCriticalMinutes(),
                        "熔断/退役等硬事件"),
                kv("softTotalPositionPct", "总仓软预算线", props.getSoftTotalPositionPct(),
                        "相对权益；仅告警，硬顶仍为 maxTotalPosition"),
                kv("softSinglePositionPct", "单票软预算线", props.getSoftSinglePositionPct(),
                        "相对权益；仅告警，硬顶仍为 maxSinglePosition"),
                kv("stopLossEnabled", "ATR 止损", props.isStopLossEnabled(), null),
                kv("trailingStopEnabled", "移动止盈", props.isTrailingStopEnabled(), null),
                kv("atrStopMultiplier", "ATR 止损倍数", props.getAtrStopMultiplier(), null),
                kv("hardStopCapitalPct", "硬止损比例", props.getHardStopCapitalPct(), "相对本金单笔最大亏损"),
                kv("maxHoldTradingDays", "最大持仓交易日", props.getMaxHoldTradingDays(),
                        "0=关闭；到期挂时间止损清仓")));
        groups.add(group("过滤",
                kv("trendFilterEnabled", "MA60 趋势过滤", props.isTrendFilterEnabled(), null),
                kv("volumeFilterEnabled", "放量确认过滤", props.isVolumeFilterEnabled(), null),
                kv("adxFilterEnabled", "ADX 过滤", props.isAdxFilterEnabled(), null),
                kv("rsiBuyMax", "买入 RSI 上限", props.getRsiBuyMax(), "超过则不开仓"),
                kv("marketCapFilterEnabled", "市值过滤", props.isMarketCapFilterEnabled(), null),
                kv("minMarketCapYi", "最小市值(亿)", props.getMinMarketCapYi(), null),
                kv("minAvgVolume20", "20日均量下限", props.getMinAvgVolume20(), "股/手口径依引擎"),
                kv("maxParticipationAdv", "单笔ADV参与率硬顶", props.getMaxParticipationAdv(),
                        "相对近20日均量；≤0关闭；止损/熔断卖出不受限"),
                kv("limitPriceProtectEnabled", "涨跌停限价保护", props.isLimitPriceProtectEnabled(),
                        "买≤涨停/卖≥跌停夹紧；无五档"),
                kv("backtestFillRatio", "回测部成比例", props.getBacktestFillRatio(),
                        "1=满额；<1 残量挂单保留"),
                kv("stressScenarioEnabled", "压力情景开关", props.isStressScenarioEnabled(), "ADV断崖等"),
                kv("stressAdvCliffRatio", "ADV断崖阈值", props.getStressAdvCliffRatio(),
                        "adv20/adv60低于此值仓位×0.5"),
                kv("signalDriftEnabled", "信号漂移监控", props.isSignalDriftEnabled(), null),
                kv("driftMinWinRate", "漂移最低滚动胜率", props.getDriftMinWinRate(), null),
                kv("driftMinIc", "漂移最低滚动IC", props.getDriftMinIc(), "MA价差vs次日收益"),
                kv("autoRetireOnSignalDrift", "漂移确认自动退役", props.isAutoRetireOnSignalDrift(),
                        "默认false，只CRITICAL告警"),
                kv("capacityAumBase", "容量基准权益", props.getCapacityAumBase(),
                        "权益超此值收紧ADV参与率"),
                kv("povMaxBarVolumePct", "POV当根量上限", props.getPovMaxBarVolumePct(),
                        "≤0关闭；单笔≤当根量×此比例"),
                kv("dataReconcileGateEnabled", "多源对账闸", props.isDataReconcileGateEnabled(), null),
                kv("dataReconcileBlockOnDiverge", "对账分歧阻断开仓", props.isDataReconcileBlockOnDiverge(),
                        "默认false仅告警"),
                kv("structuralBreakEnabled", "结构突变监控", props.isStructuralBreakEnabled(), null),
                kv("structuralBreakThreshold", "结构突变阈值", props.getStructuralBreakThreshold(), null),
                kv("stOpenFilterEnabled", "ST禁开过滤", props.isStOpenFilterEnabled(),
                        "as-of ST 禁开；涨跌幅仍按ST规则"),
                kv("turnoverGuardEnabled", "换手门禁", props.isTurnoverGuardEnabled(), null),
                kv("turnoverSoftPct", "换手软顶", props.getTurnoverSoftPct(), "日成交额/权益；降仓×0.5"),
                kv("turnoverHardPct", "换手硬顶", props.getTurnoverHardPct(), "达阈禁新开"),
                kv("stampTaxRate", "印花税(无日期回退)", props.getStampTaxRate(),
                        "有成交日时用政策as-of(2023-08-28起0.05%)"),
                kv("nextBarOpenFill", "次日开盘撮合", props.isNextBarOpenFill(), "信号次 bar 开盘成交")));
        groups.add(group("可复现",
                kv("configFingerprint", "当前配置指纹",
                        com.quant.stock.config.ConfigFingerprint.of(props),
                        "回测结果附带同字段，便于对照实验")));
        return groups;
    }

    private List<Map<String, Object>> loadSystemConfig() {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (jdbc == null || !props.isDbEnabled()) {
            return out;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT config_key, config_value, description, updated_at FROM system_config ORDER BY config_key");
            for (Map<String, Object> r : rows) {
                String key = r.get("config_key") == null ? "" : String.valueOf(r.get("config_key"));
                String desc = r.get("description") == null ? "" : String.valueOf(r.get("description")).trim();
                String label = SYSTEM_CONFIG_LABELS.get(key);
                if (label == null || label.isEmpty()) {
                    label = desc.isEmpty() ? key : desc;
                }
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("key", key);
                m.put("label", label);
                m.put("value", r.get("config_value"));
                m.put("description", desc);
                m.put("updatedAt", r.get("updated_at") == null ? null : r.get("updated_at").toString());
                out.add(m);
            }
        } catch (Exception ignored) {
            // empty
        }
        return out;
    }

    private static Map<String, Object> group(String title, Map<String, Object>... items) {
        Map<String, Object> g = new LinkedHashMap<String, Object>();
        g.put("title", title);
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> it : items) {
            list.add(it);
        }
        g.put("items", list);
        return g;
    }

    private static Map<String, Object> kv(String key, String label, Object value, String note) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("label", label);
        m.put("value", value == null ? null : String.valueOf(value));
        if (note != null && !note.isEmpty()) {
            m.put("note", note);
        }
        return m;
    }
}
