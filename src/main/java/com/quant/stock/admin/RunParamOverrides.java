package com.quant.stock.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.stock.config.QuantProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单次回测/组合回测的临时参数覆盖：仅白名单键，不落库、不影响全局与策略包。
 */
public final class RunParamOverrides {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunParamOverrides() {
    }

    /** 解析 JSON 对象字符串；非法时返回空 Map。 */
    public static Map<String, String> parseJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> raw = MAPPER.readValue(json.trim(),
                    new TypeReference<Map<String, Object>>() {
                    });
            return normalize(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("paramOverrides JSON 非法: " + e.getMessage());
        }
    }

    /** 将任意 Map 规范为白名单 string 值；非白名单键丢弃。 */
    public static Map<String, String> normalize(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, ?> e : raw.entrySet()) {
            if (e.getKey() == null || !WritableParamKeys.isWritable(e.getKey().trim())) {
                continue;
            }
            if (e.getValue() == null) {
                continue;
            }
            String s = String.valueOf(e.getValue()).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
                continue;
            }
            out.put(e.getKey().trim(), s);
        }
        return out;
    }

    /** 叠到已有快照上（就地修改）。 */
    public static void apply(QuantProperties snap, Map<String, String> overrides) {
        if (snap == null || overrides == null || overrides.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            if (!WritableParamKeys.isWritable(e.getKey())) {
                continue;
            }
            WritableParamApplier.apply(snap, e.getKey(), e.getValue());
        }
    }
}
