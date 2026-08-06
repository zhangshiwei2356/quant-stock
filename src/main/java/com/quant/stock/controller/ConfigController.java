package com.quant.stock.controller;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.strategy.BaseStrategy;
import com.quant.stock.strategy.StrategyRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公开配置（不需 API Key）：供前端判断是否启用鉴权、拉取策略列表等。
 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final QuantProperties props;
    private final StrategyRegistry strategyRegistry;

    /** 返回前端可用的公开配置（鉴权开关、限流、费率、当前策略等）。 */
    @GetMapping
    public Map<String, Object> publicConfig() {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("apiKeyRequired", StringUtils.hasText(props.getApiKey()));
        m.put("rateLimitPerMinute", props.getRateLimitPerMinute());
        m.put("historyDir", props.getHistoryDir());
        m.put("feeRate", props.getFeeRate());
        m.put("scheduleEnabled", props.getSchedule() != null && props.getSchedule().isEnabled());
        m.put("activeStrategy", strategyRegistry.active().name());
        return m;
    }

    /**
     * 已注册策略列表（回测工作台下拉用）；不含改全局激活。
     * {@code activeStrategy} 为配置默认值，页面可选其它 id 仅作用于当次回测。
     */
    @GetMapping("/strategies")
    public Map<String, Object> strategies() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (String id : strategyRegistry.ids()) {
            BaseStrategy s = strategyRegistry.resolve(id);
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", s.name());
            row.put("fingerprintId", s.fingerprintId());
            row.put("label", s.uiLabel());
            row.put("session", s instanceof com.quant.stock.session.SessionStrategy);
            if (s.profileSummary() != null && !s.profileSummary().isEmpty()) {
                row.put("summary", s.profileSummary());
            }
            list.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("activeStrategy", strategyRegistry.active().name());
        m.put("strategies", list);
        m.put("hint", "回测可选其它策略/画像；纸面扫描仍用 quant.active-strategy");
        return m;
    }
}
