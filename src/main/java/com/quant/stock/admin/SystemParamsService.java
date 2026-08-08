package com.quant.stock.admin;

import com.quant.stock.config.ConfigFingerprint;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.strategy.BaseStrategy;
import com.quant.stock.strategy.StrategyRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行参数视图 + 白名单热写：覆盖写入 {@code system_config(quant.prop.*)} 并更新 {@link QuantProperties}。
 */
@Slf4j
@Service
public class SystemParamsService {

    public static final String PROP_PREFIX = "quant.prop.";

    /** system_config 常见键的中文名（库表 description 为空时兜底） */
    private static final Map<String, String> SYSTEM_CONFIG_LABELS;

    static {
        Map<String, String> m = new HashMap<String, String>();
        m.put("sim.cash", "模拟账本现金余额");
        m.put("sim.risk.state", "模拟账户风控快照（峰值/熔断/水下日）");
        m.put("sim.retirement", "策略退役状态快照");
        m.put("sim.books.meta", "模拟挂买/挂卖与金字塔元数据");
        m.put("quant.active-strategy", "纸面激活策略（运维热切换）");
        SYSTEM_CONFIG_LABELS = Collections.unmodifiableMap(m);
    }

    private final QuantProperties props;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final ObjectProvider<EffectiveParamsService> effectiveParamsProvider;
    private final ObjectProvider<StrategyRegistry> strategyRegistryProvider;

    public SystemParamsService(QuantProperties props,
                               ObjectProvider<JdbcTemplate> jdbcProvider,
                               ObjectProvider<EffectiveParamsService> effectiveParamsProvider,
                               ObjectProvider<StrategyRegistry> strategyRegistryProvider) {
        this.props = props;
        this.jdbcProvider = jdbcProvider;
        this.effectiveParamsProvider = effectiveParamsProvider;
        this.strategyRegistryProvider = strategyRegistryProvider;
    }

    /** 启动加载 quant.prop.* 覆盖项 */
    @EventListener(ApplicationReadyEvent.class)
    public void loadOverridesOnReady() {
        Map<String, String> stored = loadPropOverrides();
        if (stored.isEmpty()) {
            return;
        }
        int n = 0;
        for (Map.Entry<String, String> e : stored.entrySet()) {
            try {
                applyToProps(e.getKey(), e.getValue());
                n++;
            } catch (Exception ex) {
                log.error("忽略非法运行参数覆盖 {}={}: {}", e.getKey(), e.getValue(), ex.getMessage(), ex);
            }
        }
        if (n > 0) {
            log.info("已从 system_config 加载 {} 项 quant.prop.* 覆盖", n);
        }
    }

    /** 展示全局配置（兼容旧调用）。 */
    public Map<String, Object> view() {
        return view(null);
    }

    /**
     * 展示全局 + 指定策略稀疏包 + 生效预览。
     *
     * @param strategyId 空则用当前激活策略
     */
    public Map<String, Object> view(String strategyId) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("dbEnabled", props.isDbEnabled());
        m.put("hint", "全局可写项保存到 system_config(quant.prop.*)；策略包保存到 strategy_param（稀疏覆盖）。"
                + "纸面/回测按策略 id 叠层生效。");
        m.put("writableKeys", new ArrayList<String>(WritableParamKeys.types().keySet()));

        StrategyRegistry registry = strategyRegistryProvider.getIfAvailable();
        EffectiveParamsService eps = effectiveParamsProvider.getIfAvailable();
        String sid = strategyId;
        if ((sid == null || sid.trim().isEmpty()) && props.getActiveStrategy() != null) {
            sid = props.getActiveStrategy().trim();
        }
        if (sid == null || sid.isEmpty()) {
            sid = StrategyRegistry.DEFAULT_ID;
        }
        Map<String, String> sparse = eps == null ? Collections.<String, String>emptyMap() : eps.getSparse(sid);
        QuantProperties effective = eps == null ? props : eps.resolve(sid);
        String fpStrategy = sid;
        if (registry != null && registry.contains(sid)) {
            fpStrategy = registry.resolve(sid).fingerprintId();
        }
        m.put("strategyId", sid);
        m.put("sparseVersion", eps == null ? null : eps.getVersion(sid));
        m.put("sparse", sparse);
        m.put("groups", buildGroups(props, effective, sparse));
        m.put("systemConfig", loadSystemConfig());
        m.put("configFingerprint", ConfigFingerprint.of(effective, fpStrategy, null));
        m.put("globalFingerprint", ConfigFingerprint.of(props));
        List<Map<String, Object>> strategies = new ArrayList<Map<String, Object>>();
        if (registry != null) {
            for (String id : registry.ids()) {
                BaseStrategy s = registry.resolve(id);
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("id", s.name());
                row.put("label", s.uiLabel());
                row.put("hasSparse", eps != null && eps.hasSparse(s.name()));
                strategies.add(row);
            }
        }
        m.put("strategies", strategies);
        return m;
    }

    /**
     * 白名单批量更新。
     *
     * @param updates key=camelCase 字段名，value=字符串或布尔/数字
     * @param confirm 必须 true
     */
    public Map<String, Object> update(Map<String, Object> updates, boolean confirm) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (!confirm) {
            out.put("ok", false);
            out.put("message", "请确认修改：confirm 须为 true");
            return out;
        }
        if (updates == null || updates.isEmpty()) {
            out.put("ok", false);
            out.put("message", "updates 不能为空");
            return out;
        }
        List<String> applied = new ArrayList<String>();
        List<String> errors = new ArrayList<String>();
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim();
            if (key.isEmpty()) {
                continue;
            }
            if (!WritableParamKeys.isWritable(key)) {
                errors.add("不在白名单: " + key);
                continue;
            }
            String raw = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            try {
                applyToProps(key, raw);
                persistProp(key, WritableParamApplier.formatStored(key, raw));
                applied.add(key);
            } catch (Exception ex) {
                log.error("系统运行参数异常", ex);
                errors.add(key + ": " + ex.getMessage());
            }
        }
        out.put("ok", errors.isEmpty());
        out.put("applied", applied);
        out.put("errors", errors);
        out.put("configFingerprint", ConfigFingerprint.of(props));
        out.put("message", errors.isEmpty()
                ? ("已更新 " + applied.size() + " 项")
                : ("部分失败：成功 " + applied.size() + "，错误 " + errors.size()));
        out.put("view", view(null));
        return out;
    }

    public static boolean isWritable(String key) {
        return WritableParamKeys.isWritable(key);
    }

    public static String writableType(String key) {
        return WritableParamKeys.typeOf(key);
    }

    private void applyToProps(String key, String raw) {
        WritableParamApplier.apply(props, key, raw);
    }

    private void persistProp(String key, String value) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null || !props.isDbEnabled()) {
            log.debug("跳过落库 quant.prop.{}（db 未启用）", key);
            return;
        }
        String cfgKey = PROP_PREFIX + key;
        jdbc.update(
                "INSERT INTO system_config(config_key, config_value, type, description) VALUES (?,?,1,?) "
                        + "ON DUPLICATE KEY UPDATE config_value=VALUES(config_value), updated_at=CURRENT_TIMESTAMP",
                cfgKey, value, "运维白名单参数 " + key);
    }

    private Map<String, String> loadPropOverrides() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null || !props.isDbEnabled()) {
            return out;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT config_key, config_value FROM system_config WHERE config_key LIKE ?",
                    PROP_PREFIX + "%");
            for (Map<String, Object> r : rows) {
                String full = r.get("config_key") == null ? "" : String.valueOf(r.get("config_key"));
                if (!full.startsWith(PROP_PREFIX)) {
                    continue;
                }
                String key = full.substring(PROP_PREFIX.length());
                if (!WritableParamKeys.isWritable(key)) {
                    continue;
                }
                Object val = r.get("config_value");
                out.put(key, val == null ? "" : String.valueOf(val));
            }
        } catch (Exception e) {
            log.error("读取 quant.prop.* 失败: {}", e.getMessage(), e);
        }
        return out;
    }

    /**
     * @param global    全局底（yml+quant.prop）
     * @param effective 生效快照（可与 global 相同）
     * @param sparse    策略稀疏覆盖；null 表示不展示覆盖字段
     */
    private List<Map<String, Object>> buildGroups(QuantProperties global, QuantProperties effective,
                                                  Map<String, String> sparse) {
        QuantProperties g = global != null ? global : props;
        QuantProperties e = effective != null ? effective : g;
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        groups.add(group("基础",
                kv("tradeMode", "交易模式", g, e, sparse, "sim=本地模拟；sdk=券商 SDK 桩"),
                kv("dbEnabled", "启用 MySQL", g, e, sparse, "false 时行情/账本等不落库"),
                kv("marketMode", "行情模式", g, e, sparse, "db / json / sdk"),
                kv("activeStrategy", "当前策略", g, e, sparse, "纸面单活策略；请用上方策略表切换"),
                kv("stockCodes", "默认股票池", g, e, sparse, "演示用代码列表"),
                kv("feeRate", "佣金费率", g, e, sparse, "双边佣金比例"),
                kv("slipPoint", "基础滑点", g, e, sparse, "回测撮合滑点"),
                kv("scheduleEnabled", "定时任务总闸", g, e, sparse,
                        "false 时不注册触发器；各任务仍以库表为准"),
                kv("historyDir", "回测历史目录", g, e, sparse, "本地 JSON 历史路径（兼容）"),
                kv("rateLimitPerMinute", "接口限流(次/分)", g, e, sparse, "回测/组合/批量；≤0 关闭")));
        groups.add(group("仓位",
                kv("maxSinglePosition", "单票最大仓位", g, e, sparse, "相对总权益比例"),
                kv("maxTotalPosition", "总仓位上限", g, e, sparse, "相对总权益比例"),
                kv("pyramidEnabled", "金字塔加仓", g, e, sparse, null),
                kv("pyramidFirst", "金字塔首仓比例", g, e, sparse, null),
                kv("pyramidSecond", "金字塔二加比例", g, e, sparse, null),
                kv("pyramidThird", "金字塔三加比例", g, e, sparse, null),
                kv("pyramidAddPct", "加仓触发涨幅", g, e, sparse, "相对成本上涨该比例可加仓")));
        groups.add(group("目标池",
                kv("tradePoolMax", "目标池上限", g, e, sparse, "盘后扫描入选 TopN"),
                kv("poolScoreMin", "入池最低综合分", g, e, sparse, "多因子打分门槛"),
                kv("poolMinListDays", "最短上市天数", g, e, sparse, null),
                kv("poolMinAvgAmount20", "20日均成交额下限(元)", g, e, sparse, "0=关闭过滤"),
                kv("poolRebuildRefreshFactors", "入池前重算因子", g, e, sparse,
                        "默认关加速；开则 pool-rebuild 先刷 factor_daily"),
                kv("poolRebuildFullBacktest", "入池完整回测", g, e, sparse,
                        "默认关=轻量扫池（指标/信号）；开则每只跑 BackTestEngine")));
        groups.add(group("风控",
                kv("dailyLossLimitPct", "单日亏损上限", g, e, sparse, "相对权益"),
                kv("consecutiveLossLimit", "连亏笔数上限", g, e, sparse, null),
                kv("drawdownReducePct", "回撤降仓阈值", g, e, sparse, "触及后缩仓"),
                kv("drawdownHaltPct", "回撤熔断阈值", g, e, sparse, "触及后停止开仓"),
                kv("drawdownDurationReduceDays", "回撤持续期降仓(日)", g, e, sparse,
                        "低于峰值满 N 交易日仓位×0.5；0=关"),
                kv("drawdownDurationHaltDays", "回撤持续期熔断(日)", g, e, sparse,
                        "低于峰值满 M 交易日熔断；0=关"),
                kv("autoRetireOnDurationHalt", "持续期熔断自动退役", g, e, sparse, null),
                kv("retirementCooldownTradingDays", "退役冷却交易日", g, e, sparse, "满后方可 resume"),
                kv("stopLossEnabled", "ATR 止损", g, e, sparse, null),
                kv("trailingStopEnabled", "移动止盈", g, e, sparse, null),
                kv("atrStopMultiplier", "ATR 止损倍数", g, e, sparse, null),
                kv("trailingAtrMultiplier", "移动止盈 ATR 倍数", g, e, sparse, null),
                kv("hardStopCapitalPct", "硬止损比例", g, e, sparse, "相对本金单笔最大亏损"),
                kv("maxHoldTradingDays", "最大持仓交易日", g, e, sparse, "0=关闭；到期挂时间止损清仓"),
                kv("maxParticipationAdv", "单笔ADV参与率硬顶", g, e, sparse, "相对近20日均量；≤0关闭"),
                kv("limitPriceProtectEnabled", "涨跌停限价保护", g, e, sparse, "买≤涨停/卖≥跌停夹紧"),
                kv("nextBarOpenFill", "次日开盘撮合", g, e, sparse, "信号次 bar 开盘成交")));
        groups.add(group("过滤",
                kv("trendFilterEnabled", "MA60 趋势过滤", g, e, sparse, null),
                kv("trendMaPeriod", "趋势均线周期", g, e, sparse, null),
                kv("volumeFilterEnabled", "放量确认过滤", g, e, sparse, null),
                kv("volumeConfirmRatio", "放量确认倍数", g, e, sparse, null),
                kv("adxFilterEnabled", "ADX 过滤", g, e, sparse, null),
                kv("adxMin", "ADX 下限", g, e, sparse, null),
                kv("adxChopMax", "ADX 震荡上限", g, e, sparse, null),
                kv("rsiBuyMax", "买入 RSI 上限", g, e, sparse, "超过则不开仓")));
        groups.add(group("可复现",
                kv("configFingerprint", "当前配置指纹", g, e, sparse, "回测结果附带同字段，便于对照实验")));
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
                    if (key.startsWith(PROP_PREFIX)) {
                        label = "运行参数覆盖 · " + key.substring(PROP_PREFIX.length());
                    } else {
                        label = desc.isEmpty() ? key : desc;
                    }
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
            log.error("系统运行参数异常", ignored);
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

    private static Map<String, Object> kv(String key, String label, QuantProperties global,
                                           QuantProperties effective, Map<String, String> sparse, String note) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("label", label);
        String globalVal = displayValue(global, key);
        String effVal = displayValue(effective, key);
        String overrideVal = sparse != null && sparse.containsKey(key) ? sparse.get(key) : null;
        boolean overridden = overrideVal != null;
        m.put("value", effVal);
        m.put("globalValue", globalVal);
        m.put("overrideValue", overrideVal);
        m.put("effectiveValue", effVal);
        m.put("overridden", overridden);
        if (note != null && !note.isEmpty()) {
            m.put("note", note);
        }
        if (isWritable(key)) {
            m.put("writable", true);
            m.put("type", writableType(key));
        } else {
            m.put("writable", false);
        }
        return m;
    }

    private static String displayValue(QuantProperties p, String key) {
        if (p == null) {
            return null;
        }
        if ("configFingerprint".equals(key)) {
            return ConfigFingerprint.of(p);
        }
        if (WritableParamKeys.isWritable(key)) {
            return WritableParamApplier.read(p, key);
        }
        if ("tradeMode".equals(key)) {
            return p.getTradeMode();
        }
        if ("dbEnabled".equals(key)) {
            return String.valueOf(p.isDbEnabled());
        }
        if ("marketMode".equals(key)) {
            return p.getMarketMode();
        }
        if ("activeStrategy".equals(key)) {
            return p.getActiveStrategy();
        }
        if ("stockCodes".equals(key)) {
            return p.getStockCodes();
        }
        if ("scheduleEnabled".equals(key)) {
            return String.valueOf(p.getSchedule() != null && p.getSchedule().isEnabled());
        }
        if ("historyDir".equals(key)) {
            return p.getHistoryDir();
        }
        if ("rateLimitPerMinute".equals(key)) {
            return String.valueOf(p.getRateLimitPerMinute());
        }
        if ("tradePoolMax".equals(key)) {
            return String.valueOf(p.getTradePoolMax());
        }
        if ("poolScoreMin".equals(key)) {
            return p.getPoolScoreMin() == null ? null : p.getPoolScoreMin().toPlainString();
        }
        if ("poolMinListDays".equals(key)) {
            return String.valueOf(p.getPoolMinListDays());
        }
        if ("poolMinAvgAmount20".equals(key)) {
            return String.valueOf(p.getPoolMinAvgAmount20());
        }
        if ("drawdownDurationReduceDays".equals(key)) {
            return String.valueOf(p.getDrawdownDurationReduceDays());
        }
        if ("drawdownDurationHaltDays".equals(key)) {
            return String.valueOf(p.getDrawdownDurationHaltDays());
        }
        if ("autoRetireOnDurationHalt".equals(key)) {
            return String.valueOf(p.isAutoRetireOnDurationHalt());
        }
        if ("retirementCooldownTradingDays".equals(key)) {
            return String.valueOf(p.getRetirementCooldownTradingDays());
        }
        return null;
    }
}
