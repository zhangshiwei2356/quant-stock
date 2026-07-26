package com.quant.stock.controller;

import com.quant.stock.admin.DataHealthService;
import com.quant.stock.admin.DataReconcileGateService;
import com.quant.stock.admin.IndustryReclassService;
import com.quant.stock.admin.SystemParamsService;
import com.quant.stock.risk.StPitService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
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
    private final DataReconcileGateService dataReconcileGateService;
    private final ObjectProvider<StPitService> stPitProvider;
    private final ObjectProvider<IndustryReclassService> industryReclassProvider;

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

    /** 多源对账闸最近结果（P0-107） */
    @GetMapping("/data-reconcile")
    public Map<String, Object> dataReconcile() {
        return dataReconcileGateService.lastReport();
    }

    /** 立即跑一轮日线 vs 分钟聚合对账 */
    @PostMapping("/data-reconcile/run")
    public Map<String, Object> dataReconcileRun() {
        return dataReconcileGateService.reconcile(null);
    }

    /** ST as-of / 财报时钟边界（P0-101） */
    @GetMapping("/st-pit")
    public Map<String, Object> stPit() {
        StPitService svc = stPitProvider.getIfAvailable();
        if (svc == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("hint", "需要 quant.db-enabled=true");
            m.put("earningsClock", Collections.singletonMap("available", false));
            return m;
        }
        Map<String, Object> m = svc.status();
        m.put("recent", svc.recent(30));
        return m;
    }

    @PostMapping("/st-pit")
    public Map<String, Object> stPitUpsert(@RequestParam String symbol,
                                           @RequestParam String effectiveDate,
                                           @RequestParam boolean isSt,
                                           @RequestParam(required = false) String note) {
        StPitService svc = stPitProvider.getIfAvailable();
        if (svc == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return svc.upsert(symbol, LocalDate.parse(effectiveDate), isSt, note);
    }

    /** 行业 reclass as-of 日志（P0-121） */
    @GetMapping("/industry-reclass")
    public Map<String, Object> industryReclass() {
        IndustryReclassService svc = industryReclassProvider.getIfAvailable();
        if (svc == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("hint", "需要 quant.db-enabled=true");
            return m;
        }
        return svc.status();
    }

    @PostMapping("/industry-reclass/sync")
    public Map<String, Object> industryReclassSync() {
        IndustryReclassService svc = industryReclassProvider.getIfAvailable();
        if (svc == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return svc.syncFromStockBasic();
    }
}
