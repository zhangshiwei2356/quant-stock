package com.quant.stock.strategy;

import com.quant.stock.config.QuantProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单活策略注册表：收集全部 {@link BaseStrategy} Bean，按 {@link BaseStrategy#name()} 索引；
 * 当前实例由 {@code quant.active-strategy} 决定（默认 {@code maCross}）。
 */
@Component
public class StrategyRegistry {

    public static final String DEFAULT_ID = "maCross";

    private final QuantProperties props;
    private final Map<String, BaseStrategy> byId;

    public StrategyRegistry(List<BaseStrategy> strategies, QuantProperties props) {
        this.props = props;
        Map<String, BaseStrategy> m = new LinkedHashMap<String, BaseStrategy>();
        if (strategies != null) {
            for (BaseStrategy s : strategies) {
                if (s == null || s.name() == null || s.name().trim().isEmpty()) {
                    continue;
                }
                String id = normalize(s.name());
                for (Map.Entry<String, BaseStrategy> e : m.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(id)) {
                        throw new IllegalStateException("重复策略 id: " + id
                                + " (" + e.getValue().getClass().getSimpleName()
                                + " vs " + s.getClass().getSimpleName() + ")");
                    }
                }
                m.put(id, s);
            }
        }
        this.byId = Collections.unmodifiableMap(m);
    }

    /** 当前配置激活的策略；未知 id 时回退 {@link #DEFAULT_ID}。 */
    public BaseStrategy active() {
        return resolve(props != null ? props.getActiveStrategy() : DEFAULT_ID);
    }

    /**
     * 按 id 解析策略；空/未知回退 maCross（若未注册则抛错）。大小写不敏感。
     */
    public BaseStrategy resolve(String id) {
        String key = normalize(id);
        if (key.isEmpty()) {
            key = DEFAULT_ID;
        }
        BaseStrategy s = findIgnoreCase(key);
        if (s == null && !DEFAULT_ID.equalsIgnoreCase(key)) {
            s = findIgnoreCase(DEFAULT_ID);
        }
        if (s == null) {
            throw new IllegalStateException("未注册策略 id=" + key + "，已知=" + byId.keySet());
        }
        return s;
    }

    /** 已注册策略 id 列表（稳定顺序，保留实现类 {@link BaseStrategy#name()} 原样）。 */
    public List<String> ids() {
        return new ArrayList<String>(byId.keySet());
    }

    public boolean contains(String id) {
        return findIgnoreCase(normalize(id)) != null;
    }

    private BaseStrategy findIgnoreCase(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        BaseStrategy exact = byId.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, BaseStrategy> e : byId.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String normalize(String id) {
        if (id == null) {
            return "";
        }
        return id.trim();
    }
}
