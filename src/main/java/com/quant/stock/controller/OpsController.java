package com.quant.stock.controller;

import com.quant.stock.admin.ActiveStrategyService;
import com.quant.stock.admin.DataHealthService;
import com.quant.stock.admin.DataReconcileGateService;
import com.quant.stock.admin.EffectiveParamsService;
import com.quant.stock.admin.IndustryReclassService;
import com.quant.stock.admin.SystemParamsService;
import com.quant.stock.kuangrui.KuangruiMdsOpsFacade;
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
import java.util.List;
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
    private final EffectiveParamsService effectiveParamsService;
    private final DataReconcileGateService dataReconcileGateService;
    private final ActiveStrategyService activeStrategyService;
    private final ObjectProvider<StPitService> stPitProvider;
    private final ObjectProvider<IndustryReclassService> industryReclassProvider;
    private final ObjectProvider<KuangruiMdsOpsFacade> kuangruiMdsOpsProvider;

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

    /** 运行参数视图：可选 strategyId 展示该策略稀疏包与生效预览。 */
    @GetMapping("/params")
    public Map<String, Object> params(@RequestParam(value = "strategyId", required = false) String strategyId) {
        return systemParamsService.view(strategyId);
    }

    /**
     * 全局白名单热写（quant.prop.*）。body: {@code updates} + {@code confirm:true}。
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

    /**
     * 策略稀疏参数包。body: strategyId, updates?, clearKeys?, confirm:true, version?
     */
    @PostMapping("/strategy-params")
    public Map<String, Object> updateStrategyParams(@RequestBody Map<String, Object> body) {
        String strategyId = body == null || body.get("strategyId") == null
                ? null : String.valueOf(body.get("strategyId")).trim();
        Map<String, Object> updates = null;
        if (body != null && body.get("updates") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> u = (Map<String, Object>) body.get("updates");
            updates = u;
        }
        List<String> clearKeys = null;
        if (body != null && body.get("clearKeys") instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> ck = (List<String>) body.get("clearKeys");
            clearKeys = ck;
        }
        boolean confirm = body != null && Boolean.TRUE.equals(body.get("confirm"));
        Integer version = null;
        if (body != null && body.get("version") != null) {
            try {
                version = Integer.valueOf(String.valueOf(body.get("version")));
            } catch (Exception ignored) {
                // leave null
            }
        }
        Map<String, Object> out = effectiveParamsService.saveSparse(strategyId, updates, clearKeys, confirm, version);
        if (Boolean.TRUE.equals(out.get("ok")) || out.get("view") == null) {
            out.put("view", systemParamsService.view(strategyId));
        }
        return out;
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

    /** 宽睿 MDS 状态（默认 noop；-Pkuangrui + 开关开启后为真实客户端）。 */
    @GetMapping("/kuangrui/mds/status")
    public Map<String, Object> kuangruiMdsStatus() {
        KuangruiMdsOpsFacade f = kuangruiMdsOpsProvider.getIfAvailable();
        if (f == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("live", false);
            m.put("hint", "需要 quant.db-enabled=true");
            return m;
        }
        return f.status();
    }

    /** MDS 查询通道拉取快照并落库 market_1min(MDS)。 */
    @PostMapping("/kuangrui/mds/pull")
    public Map<String, Object> kuangruiMdsPull() {
        KuangruiMdsOpsFacade f = kuangruiMdsOpsProvider.getIfAvailable();
        if (f == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return f.pull();
    }

    /** 启动 MDS L1 TCP 订阅（目标池或配置标的）。 */
    @PostMapping("/kuangrui/mds/subscribe")
    public Map<String, Object> kuangruiMdsSubscribe() {
        KuangruiMdsOpsFacade f = kuangruiMdsOpsProvider.getIfAvailable();
        if (f == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return f.startSubscribe();
    }

    /** 停止 MDS 订阅并关闭连接。 */
    @PostMapping("/kuangrui/mds/stop")
    public Map<String, Object> kuangruiMdsStop() {
        KuangruiMdsOpsFacade f = kuangruiMdsOpsProvider.getIfAvailable();
        if (f == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return f.stopSubscribe();
    }

    /** 将 MDS 分钟桶刷入 market_1min。 */
    @PostMapping("/kuangrui/mds/flush")
    public Map<String, Object> kuangruiMdsFlush() {
        KuangruiMdsOpsFacade f = kuangruiMdsOpsProvider.getIfAvailable();
        if (f == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return f.flush();
    }
}
