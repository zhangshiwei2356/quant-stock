package com.quant.stock.controller;

import com.quant.stock.admin.ActiveStrategyService;
import com.quant.stock.admin.DataHealthService;
import com.quant.stock.admin.DataReconcileGateService;
import com.quant.stock.admin.IndustryReclassService;
import com.quant.stock.admin.SystemParamsService;
import com.quant.stock.risk.StPitService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维：数据健康、运行参数、对账闸、激活策略切换。
 */
@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class OpsController {

    private final ObjectProvider<DataHealthService> dataHealthProvider;
    private final SystemParamsService systemParamsService;
    private final DataReconcileGateService dataReconcileGateService;
    private final ActiveStrategyService activeStrategyService;
    private final ObjectProvider<StPitService> stPitProvider;
    private final ObjectProvider<IndustryReclassService> industryReclassProvider;

    /** 全市场数据健康抽检（覆盖率、缺口、异常项）。 */
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

    /** 运行参数与配置指纹视图（含白名单可写标记）。 */
    @GetMapping("/params")
    public Map<String, Object> params() {
        return systemParamsService.view();
    }

    /**
     * 白名单参数热写。body: {@code updates} map + {@code confirm:true}。
     */
    @PostMapping("/params")
    public Map<String, Object> updateParams(@RequestBody Map<String, Object> body) {
        Map<String, Object> updates = null;
        if (body != null && body.get("updates") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> u = (Map<String, Object>) body.get("updates");
            updates = u;
        } else if (body != null && body.get("updates") != null) {
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("ok", false);
            err.put("message", "updates 须为对象");
            return err;
        }
        boolean confirm = body != null && Boolean.TRUE.equals(body.get("confirm"));
        return systemParamsService.update(updates, confirm);
    }

    /** 已注册策略 + 当前纸面激活 id */
    @GetMapping("/strategies")
    public Map<String, Object> strategies() {
        return activeStrategyService.listView();
    }

    /**
     * 切换纸面激活策略。body: {@code strategyId}, {@code confirm:true}。
     */
    @PostMapping("/active-strategy")
    public Map<String, Object> switchActiveStrategy(@RequestBody Map<String, Object> body) {
        Object rawId = body == null ? null : body.get("strategyId");
        String id = rawId == null ? null : String.valueOf(rawId).trim();
        if (id != null && (id.isEmpty() || "null".equalsIgnoreCase(id))) {
            id = null;
        }
        boolean confirm = body != null && Boolean.TRUE.equals(body.get("confirm"));
        return activeStrategyService.switchActive(id, confirm);
    }

    /** 行情自洽闸最近结果（原 P0-107；现检查 market_1min） */
    @GetMapping("/data-reconcile")
    public Map<String, Object> dataReconcile() {
        return dataReconcileGateService.lastReport();
    }

    /** 立即跑一轮 market_1min 自洽检查 */
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

    /** 维护 ST 时点记录（按生效日 as-of 标记是否 ST）。 */
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

    /** 同步行业重分类快照（需 db-enabled）。 */
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
