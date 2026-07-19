package com.quant.stock.admin;

import com.quant.stock.config.QuantProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行参数只读视图：QuantProperties 生效值 + system_config 表键值。
 */
@Service
public class SystemParamsService {

    private final QuantProperties props;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public SystemParamsService(QuantProperties props, ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.props = props;
        this.jdbcProvider = jdbcProvider;
    }

    public Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("dbEnabled", props.isDbEnabled());
        m.put("hint", "只读展示当前生效配置；修改请改 application.yml / 环境变量或 system_config（部分键）。");
        m.put("groups", buildGroups());
        m.put("systemConfig", loadSystemConfig());
        return m;
    }

    private List<Map<String, Object>> buildGroups() {
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        groups.add(group("基础",
                kv("tradeMode", props.getTradeMode()),
                kv("dbEnabled", props.isDbEnabled()),
                kv("marketMode", props.getMarketMode()),
                kv("stockCodes", props.getStockCodes()),
                kv("feeRate", props.getFeeRate()),
                kv("slipPoint", props.getSlipPoint()),
                kv("scheduleEnabled", props.getSchedule() != null && props.getSchedule().isEnabled()),
                kv("historyDir", props.getHistoryDir()),
                kv("rateLimitPerMinute", props.getRateLimitPerMinute())));
        groups.add(group("仓位",
                kv("maxSinglePosition", props.getMaxSinglePosition()),
                kv("maxTotalPosition", props.getMaxTotalPosition()),
                kv("pyramidEnabled", props.isPyramidEnabled()),
                kv("pyramidFirst/Second/Third",
                        props.getPyramidFirst() + " / " + props.getPyramidSecond() + " / " + props.getPyramidThird()),
                kv("pyramidAddPct", props.getPyramidAddPct())));
        groups.add(group("目标池",
                kv("tradePoolMax", props.getTradePoolMax()),
                kv("poolScoreMin", props.getPoolScoreMin()),
                kv("poolMinListDays", props.getPoolMinListDays()),
                kv("poolMinAvgAmount20", props.getPoolMinAvgAmount20())));
        groups.add(group("风控",
                kv("dailyLossLimitPct", props.getDailyLossLimitPct()),
                kv("consecutiveLossLimit", props.getConsecutiveLossLimit()),
                kv("drawdownReducePct", props.getDrawdownReducePct()),
                kv("drawdownHaltPct", props.getDrawdownHaltPct()),
                kv("stopLossEnabled", props.isStopLossEnabled()),
                kv("trailingStopEnabled", props.isTrailingStopEnabled()),
                kv("atrStopMultiplier", props.getAtrStopMultiplier()),
                kv("hardStopCapitalPct", props.getHardStopCapitalPct())));
        groups.add(group("过滤",
                kv("trendFilterEnabled", props.isTrendFilterEnabled()),
                kv("volumeFilterEnabled", props.isVolumeFilterEnabled()),
                kv("adxFilterEnabled", props.isAdxFilterEnabled()),
                kv("rsiBuyMax", props.getRsiBuyMax()),
                kv("marketCapFilterEnabled", props.isMarketCapFilterEnabled()),
                kv("minMarketCapYi", props.getMinMarketCapYi()),
                kv("minAvgVolume20", props.getMinAvgVolume20()),
                kv("nextBarOpenFill", props.isNextBarOpenFill())));
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
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("key", r.get("config_key"));
                m.put("value", r.get("config_value"));
                m.put("description", r.get("description"));
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

    private static Map<String, Object> kv(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("value", value == null ? null : String.valueOf(value));
        return m;
    }
}
