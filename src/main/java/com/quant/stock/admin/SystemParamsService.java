package com.quant.stock.admin;

import com.quant.stock.config.ConfigFingerprint;
import com.quant.stock.config.QuantProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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

    /** 可写键 → 类型：bool / int / long / decimal */
    private static final Map<String, String> WRITABLE_TYPES;

    static {
        Map<String, String> m = new HashMap<String, String>();
        m.put("sim.cash", "模拟账本现金余额");
        m.put("sim.risk.state", "模拟账户风控快照（峰值/熔断/水下日）");
        m.put("sim.retirement", "策略退役状态快照");
        m.put("sim.books.meta", "模拟挂买/挂卖与金字塔元数据");
        m.put("quant.active-strategy", "纸面激活策略（运维热切换）");
        SYSTEM_CONFIG_LABELS = Collections.unmodifiableMap(m);

        Map<String, String> w = new LinkedHashMap<String, String>();
        // 过滤
        w.put("trendFilterEnabled", "bool");
        w.put("trendMaPeriod", "int");
        w.put("volumeFilterEnabled", "bool");
        w.put("volumeConfirmRatio", "decimal");
        w.put("adxFilterEnabled", "bool");
        w.put("adxMin", "decimal");
        w.put("adxChopMax", "decimal");
        w.put("rsiBuyMax", "decimal");
        // 仓位 / 金字塔
        w.put("maxSinglePosition", "decimal");
        w.put("maxTotalPosition", "decimal");
        w.put("pyramidEnabled", "bool");
        w.put("pyramidFirst", "decimal");
        w.put("pyramidSecond", "decimal");
        w.put("pyramidThird", "decimal");
        w.put("pyramidAddPct", "decimal");
        // 止损
        w.put("stopLossEnabled", "bool");
        w.put("atrStopMultiplier", "decimal");
        w.put("hardStopCapitalPct", "decimal");
        w.put("trailingStopEnabled", "bool");
        w.put("trailingAtrMultiplier", "decimal");
        w.put("maxHoldTradingDays", "int");
        // 成本 / 执行常用
        w.put("feeRate", "decimal");
        w.put("slipPoint", "decimal");
        w.put("maxParticipationAdv", "decimal");
        w.put("limitPriceProtectEnabled", "bool");
        w.put("nextBarOpenFill", "bool");
        // 账户风控常用
        w.put("dailyLossLimitPct", "decimal");
        w.put("consecutiveLossLimit", "int");
        w.put("drawdownReducePct", "decimal");
        w.put("drawdownHaltPct", "decimal");
        WRITABLE_TYPES = Collections.unmodifiableMap(w);
    }

    private final QuantProperties props;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public SystemParamsService(QuantProperties props, ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.props = props;
        this.jdbcProvider = jdbcProvider;
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
                log.warn("忽略非法运行参数覆盖 {}={}: {}", e.getKey(), e.getValue(), ex.getMessage());
            }
        }
        if (n > 0) {
            log.info("已从 system_config 加载 {} 项 quant.prop.* 覆盖", n);
        }
    }

    /** 展示当前生效配置；白名单项带 writable/type */
    public Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("dbEnabled", props.isDbEnabled());
        m.put("hint", "带「可写」的项可在运维页修改（写入 system_config 并热生效）；"
                + "激活策略请用上方策略表切换。");
        m.put("writableKeys", new ArrayList<String>(WRITABLE_TYPES.keySet()));
        m.put("groups", buildGroups());
        m.put("systemConfig", loadSystemConfig());
        m.put("configFingerprint", ConfigFingerprint.of(props));
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
            if (!WRITABLE_TYPES.containsKey(key)) {
                errors.add("不在白名单: " + key);
                continue;
            }
            String raw = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            try {
                applyToProps(key, raw);
                persistProp(key, formatStored(key, raw));
                applied.add(key);
            } catch (Exception ex) {
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
        out.put("view", view());
        return out;
    }

    public static boolean isWritable(String key) {
        return key != null && WRITABLE_TYPES.containsKey(key);
    }

    public static String writableType(String key) {
        return WRITABLE_TYPES.get(key);
    }

    private void applyToProps(String key, String raw) {
        String type = WRITABLE_TYPES.get(key);
        if (type == null) {
            throw new IllegalArgumentException("非白名单键");
        }
        if ("bool".equals(type)) {
            boolean v = parseBool(raw);
            if ("trendFilterEnabled".equals(key)) {
                props.setTrendFilterEnabled(v);
            } else if ("volumeFilterEnabled".equals(key)) {
                props.setVolumeFilterEnabled(v);
            } else if ("adxFilterEnabled".equals(key)) {
                props.setAdxFilterEnabled(v);
            } else if ("pyramidEnabled".equals(key)) {
                props.setPyramidEnabled(v);
            } else if ("stopLossEnabled".equals(key)) {
                props.setStopLossEnabled(v);
            } else if ("trailingStopEnabled".equals(key)) {
                props.setTrailingStopEnabled(v);
            } else if ("limitPriceProtectEnabled".equals(key)) {
                props.setLimitPriceProtectEnabled(v);
            } else if ("nextBarOpenFill".equals(key)) {
                props.setNextBarOpenFill(v);
            } else {
                throw new IllegalArgumentException("未绑定 bool 键");
            }
            return;
        }
        if ("int".equals(type)) {
            int v = Integer.parseInt(raw);
            if ("trendMaPeriod".equals(key)) {
                props.setTrendMaPeriod(v);
            } else if ("maxHoldTradingDays".equals(key)) {
                props.setMaxHoldTradingDays(v);
            } else if ("consecutiveLossLimit".equals(key)) {
                props.setConsecutiveLossLimit(v);
            } else {
                throw new IllegalArgumentException("未绑定 int 键");
            }
            return;
        }
        if ("long".equals(type)) {
            throw new IllegalArgumentException("未绑定 long 键");
        }
        if ("decimal".equals(type)) {
            BigDecimal v = new BigDecimal(raw);
            if ("volumeConfirmRatio".equals(key)) {
                props.setVolumeConfirmRatio(v);
            } else if ("adxMin".equals(key)) {
                props.setAdxMin(v);
            } else if ("adxChopMax".equals(key)) {
                props.setAdxChopMax(v);
            } else if ("rsiBuyMax".equals(key)) {
                props.setRsiBuyMax(v);
            } else if ("maxSinglePosition".equals(key)) {
                props.setMaxSinglePosition(v);
            } else if ("maxTotalPosition".equals(key)) {
                props.setMaxTotalPosition(v);
            } else if ("pyramidFirst".equals(key)) {
                props.setPyramidFirst(v);
            } else if ("pyramidSecond".equals(key)) {
                props.setPyramidSecond(v);
            } else if ("pyramidThird".equals(key)) {
                props.setPyramidThird(v);
            } else if ("pyramidAddPct".equals(key)) {
                props.setPyramidAddPct(v);
            } else if ("atrStopMultiplier".equals(key)) {
                props.setAtrStopMultiplier(v);
            } else if ("hardStopCapitalPct".equals(key)) {
                props.setHardStopCapitalPct(v);
            } else if ("trailingAtrMultiplier".equals(key)) {
                props.setTrailingAtrMultiplier(v);
            } else if ("feeRate".equals(key)) {
                props.setFeeRate(v);
            } else if ("slipPoint".equals(key)) {
                props.setSlipPoint(v);
            } else if ("maxParticipationAdv".equals(key)) {
                props.setMaxParticipationAdv(v);
            } else if ("dailyLossLimitPct".equals(key)) {
                props.setDailyLossLimitPct(v);
            } else if ("drawdownReducePct".equals(key)) {
                props.setDrawdownReducePct(v);
            } else if ("drawdownHaltPct".equals(key)) {
                props.setDrawdownHaltPct(v);
            } else {
                throw new IllegalArgumentException("未绑定 decimal 键");
            }
            return;
        }
        throw new IllegalArgumentException("未知类型 " + type);
    }

    private static boolean parseBool(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("布尔值不能为空");
        }
        String s = raw.trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s)) {
            return true;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "off".equals(s)) {
            return false;
        }
        throw new IllegalArgumentException("非法布尔: " + raw);
    }

    private String formatStored(String key, String raw) {
        String type = WRITABLE_TYPES.get(key);
        if ("bool".equals(type)) {
            return String.valueOf(parseBool(raw));
        }
        if ("int".equals(type)) {
            return String.valueOf(Integer.parseInt(raw.trim()));
        }
        if ("decimal".equals(type)) {
            return new BigDecimal(raw.trim()).toPlainString();
        }
        return raw.trim();
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
                if (!WRITABLE_TYPES.containsKey(key)) {
                    continue;
                }
                Object val = r.get("config_value");
                out.put(key, val == null ? "" : String.valueOf(val));
            }
        } catch (Exception e) {
            log.warn("读取 quant.prop.* 失败: {}", e.getMessage());
        }
        return out;
    }

    private List<Map<String, Object>> buildGroups() {
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        groups.add(group("基础",
                kv("tradeMode", "交易模式", props.getTradeMode(), "sim=本地模拟；sdk=券商 SDK 桩"),
                kv("dbEnabled", "启用 MySQL", props.isDbEnabled(), "false 时行情/账本等不落库"),
                kv("marketMode", "行情模式", props.getMarketMode(), "db / json / sdk"),
                kv("activeStrategy", "当前策略", props.getActiveStrategy(),
                        "纸面单活策略；请用上方策略表切换"),
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
                kv("pyramidFirst", "金字塔首仓比例", props.getPyramidFirst(), null),
                kv("pyramidSecond", "金字塔二加比例", props.getPyramidSecond(), null),
                kv("pyramidThird", "金字塔三加比例", props.getPyramidThird(), null),
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
                kv("stopLossEnabled", "ATR 止损", props.isStopLossEnabled(), null),
                kv("trailingStopEnabled", "移动止盈", props.isTrailingStopEnabled(), null),
                kv("atrStopMultiplier", "ATR 止损倍数", props.getAtrStopMultiplier(), null),
                kv("trailingAtrMultiplier", "移动止盈 ATR 倍数", props.getTrailingAtrMultiplier(), null),
                kv("hardStopCapitalPct", "硬止损比例", props.getHardStopCapitalPct(), "相对本金单笔最大亏损"),
                kv("maxHoldTradingDays", "最大持仓交易日", props.getMaxHoldTradingDays(),
                        "0=关闭；到期挂时间止损清仓"),
                kv("maxParticipationAdv", "单笔ADV参与率硬顶", props.getMaxParticipationAdv(),
                        "相对近20日均量；≤0关闭"),
                kv("limitPriceProtectEnabled", "涨跌停限价保护", props.isLimitPriceProtectEnabled(),
                        "买≤涨停/卖≥跌停夹紧"),
                kv("nextBarOpenFill", "次日开盘撮合", props.isNextBarOpenFill(), "信号次 bar 开盘成交")));
        groups.add(group("过滤",
                kv("trendFilterEnabled", "MA60 趋势过滤", props.isTrendFilterEnabled(), null),
                kv("trendMaPeriod", "趋势均线周期", props.getTrendMaPeriod(), null),
                kv("volumeFilterEnabled", "放量确认过滤", props.isVolumeFilterEnabled(), null),
                kv("volumeConfirmRatio", "放量确认倍数", props.getVolumeConfirmRatio(), null),
                kv("adxFilterEnabled", "ADX 过滤", props.isAdxFilterEnabled(), null),
                kv("adxMin", "ADX 下限", props.getAdxMin(), null),
                kv("adxChopMax", "ADX 震荡上限", props.getAdxChopMax(), null),
                kv("rsiBuyMax", "买入 RSI 上限", props.getRsiBuyMax(), "超过则不开仓")));
        groups.add(group("可复现",
                kv("configFingerprint", "当前配置指纹", ConfigFingerprint.of(props),
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
        if (isWritable(key)) {
            m.put("writable", true);
            m.put("type", writableType(key));
        } else {
            m.put("writable", false);
        }
        return m;
    }
}
