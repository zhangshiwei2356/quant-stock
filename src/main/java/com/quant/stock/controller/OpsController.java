package com.quant.stock.controller;

import com.quant.stock.admin.DataHealthService;
import com.quant.stock.admin.SystemParamsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维只读：数据健康、运行参数。
 */
@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class OpsController {

    private final ObjectProvider<DataHealthService> dataHealthProvider;
    private final SystemParamsService systemParamsService;

    @GetMapping("/data-health")
    public Map<String, Object> dataHealth() {
        DataHealthService svc = dataHealthProvider.getIfAvailable();
        if (svc == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("universeSize", 0);
            m.put("okCount", 0);
            m.put("warnCount", 0);
            m.put("items", Collections.emptyList());
            m.put("hint", "需要 quant.db-enabled=true");
            return m;
        }
        return svc.check();
    }

    @GetMapping("/params")
    public Map<String, Object> params() {
        return systemParamsService.view();
    }
}
