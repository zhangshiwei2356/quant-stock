package com.quant.stock.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.stock.config.QuantProperties;

/**
 * 深拷贝 {@link QuantProperties}，供策略包叠层生成生效快照。
 */
public final class QuantPropertiesCopy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QuantPropertiesCopy() {
    }

    public static QuantProperties copy(QuantProperties src) {
        if (src == null) {
            return new QuantProperties();
        }
        try {
            return MAPPER.readValue(MAPPER.writeValueAsString(src), QuantProperties.class);
        } catch (Exception e) {
            throw new IllegalStateException("QuantProperties 拷贝失败: " + e.getMessage(), e);
        }
    }
}
