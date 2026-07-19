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
        SYSTEM_CONFIG_LABELS = Collections.unmodifiableMap(m);
    }

    private final QuantProperties props;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public SystemParamsService(QuantProperties props, ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.props = props;
        this.jdbcProvider = jdbcProvider;
    }

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
                kv("stopLossEnabled", "ATR 止损", props.isStopLossEnabled(), null),
                kv("trailingStopEnabled", "移动止盈", props.isTrailingStopEnabled(), null),
                kv("atrStopMultiplier", "ATR 止损倍数", props.getAtrStopMultiplier(), null),
                kv("hardStopCapitalPct", "硬止损比例", props.getHardStopCapitalPct(), "相对本金单笔最大亏损")));
        groups.add(group("过滤",
                kv("trendFilterEnabled", "MA60 趋势过滤", props.isTrendFilterEnabled(), null),
                kv("volumeFilterEnabled", "放量确认过滤", props.isVolumeFilterEnabled(), null),
                kv("adxFilterEnabled", "ADX 过滤", props.isAdxFilterEnabled(), null),
                kv("rsiBuyMax", "买入 RSI 上限", props.getRsiBuyMax(), "超过则不开仓"),
                kv("marketCapFilterEnabled", "市值过滤", props.isMarketCapFilterEnabled(), null),
                kv("minMarketCapYi", "最小市值(亿)", props.getMinMarketCapYi(), null),
                kv("minAvgVolume20", "20日均量下限", props.getMinAvgVolume20(), "股/手口径依引擎"),
                kv("nextBarOpenFill", "次日开盘撮合", props.isNextBarOpenFill(), "信号次 bar 开盘成交")));
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
