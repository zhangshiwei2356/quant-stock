package com.quant.stock.strategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 回测历史 {@code strategy_id} 与注册表 id / 指纹兼容名之间的映射。
 * <p>
 * 策略评估按注册 id（如 {@code maCross}）查询；旧数据可能为空，或写入了
 * {@link BaseStrategy#fingerprintId()}（如 {@code MaCrossStrategy}），导致无法关联。
 * </p>
 */
public final class StrategyIdAliases {

    private static final Map<String, String> ALIAS_TO_CANONICAL;

    static {
        Map<String, String> m = new LinkedHashMap<String, String>();
        // 金叉：指纹名 / 历史配置名 → 注册 id
        m.put("macrossstrategy", "maCross");
        m.put("ma_cross_filtered", "maCross");
        m.put("macross", "maCross");
        ALIAS_TO_CANONICAL = Collections.unmodifiableMap(m);
    }

    private StrategyIdAliases() {
    }

    /**
     * 将库内或入参 id 规范为注册表 id；无法识别则返回 trim 后原值（可能仍未知）。
     */
    public static String toCanonical(String raw, StrategyRegistry registry) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (registry != null && registry.contains(t)) {
            return registry.resolve(t).name();
        }
        String mapped = ALIAS_TO_CANONICAL.get(t.toLowerCase(Locale.ROOT));
        if (mapped != null) {
            if (registry == null || registry.contains(mapped)) {
                return mapped;
            }
        }
        return t;
    }

    /**
     * 查询某注册策略时应匹配的全部库内可能取值（含自身与别名）。
     */
    public static List<String> matchIdsForQuery(String canonicalId) {
        if (canonicalId == null || canonicalId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String canon = canonicalId.trim();
        Set<String> out = new LinkedHashSet<String>();
        out.add(canon);
        if ("maCross".equalsIgnoreCase(canon)) {
            out.add("maCross");
            out.add("MaCrossStrategy");
            out.add("MA_CROSS_FILTERED");
            out.add("MaCross");
            out.add("MACROSS");
        }
        return new ArrayList<String>(out);
    }

    /** 已知别名 → 规范 id（小写键）。 */
    public static Map<String, String> aliasMap() {
        return ALIAS_TO_CANONICAL;
    }
}
