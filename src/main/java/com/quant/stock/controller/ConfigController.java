package com.quant.stock.controller;

import com.quant.stock.config.QuantProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 公开配置（不需 API Key）：供前端判断是否启用鉴权。
 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final QuantProperties props;

    @GetMapping
    public Map<String, Object> publicConfig() {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("apiKeyRequired", StringUtils.hasText(props.getApiKey()));
        m.put("rateLimitPerMinute", props.getRateLimitPerMinute());
        m.put("historyDir", props.getHistoryDir());
        m.put("feeRate", props.getFeeRate());
        return m;
    }
}
