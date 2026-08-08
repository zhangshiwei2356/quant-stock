package com.quant.stock.controller;


import lombok.extern.slf4j.Slf4j;
import com.quant.stock.admin.ActiveStrategyService;
import com.quant.stock.admin.DataHealthService;
import com.quant.stock.admin.DataReconcileGateService;
import com.quant.stock.admin.EffectiveParamsService;
import com.quant.stock.admin.IndustryReclassService;
import com.quant.stock.admin.MarketSourceSampleReconcileService;
import com.quant.stock.admin.SystemParamsService;
import com.quant.stock.backtest.BacktestStrategyIdBackfillService;
import com.quant.stock.kuangrui.KuangruiAccountLoginService;
import com.quant.stock.kuangrui.KuangruiMdsOpsFacade;
import com.quant.stock.kuangrui.KuangruiOesOpsFacade;
import com.quant.stock.market.FactorDailyComputeService;
import com.quant.stock.market.TdxScriptBackfillService;
import com.quant.stock.pool.TradePoolService;
import com.quant.stock.risk.StPitService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维：数据健康、运行参数、分钟行情自洽、激活策略切换。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class OpsController {

    private final ObjectProvider<DataHealthService> dataHealthProvider;
    private final ObjectProvider<MarketSourceSampleReconcileService> mdsTdxSampleProvider;
    private final SystemParamsService systemParamsService;
    private final EffectiveParamsService effectiveParamsService;
    private final DataReconcileGateService dataReconcileGateService;
    private final ActiveStrategyService activeStrategyService;
    private final ObjectProvider<StPitService> stPitProvider;
    private final ObjectProvider<IndustryReclassService> industryReclassProvider;
    private final ObjectProvider<KuangruiMdsOpsFacade> kuangruiMdsOpsProvider;
    private final ObjectProvider<KuangruiOesOpsFacade> kuangruiOesOpsProvider;
    private final ObjectProvider<KuangruiAccountLoginService> kuangruiAccountLoginProvider;
    private final ObjectProvider<FactorDailyComputeService> factorDailyComputeProvider;
    private final ObjectProvider<TradePoolService> tradePoolProvider;
    private final ObjectProvider<TdxScriptBackfillService> tdxScriptBackfillProvider;
    private final ObjectProvider<BacktestStrategyIdBackfillService> backtestStrategyIdBackfillProvider;

    /** 最近一次覆盖检查结果（未跑过则提示刷新）；若正在跑则 running=true。 */
    @GetMapping("/data-health")
    public Map<String, Object> dataHealth() {
        DataHealthService svc = dataHealthProvider.getIfAvailable();
        if (svc == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("universeSize", 0);
            m.put("okCount", 0);
            m.put("warnCount", 0);
            m.put("specialCount", 0);
            m.put("items", Collections.emptyList());
            m.put("specialItems", Collections.emptyList());
            m.put("hint", "需要 quant.db-enabled=true");
            return m;
        }
        return svc.lastResult();
    }

    /** 异步启动覆盖检查（全市场日线 + 目标池分钟）；进度见 /data-health/status。 */
    @PostMapping("/data-health/run")
    public Map<String, Object> dataHealthRun() {
        DataHealthService svc = dataHealthProvider.getIfAvailable();
        if (svc == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        try {
            return svc.startAsync();
        } catch (IllegalStateException e) {
            log.error("运维接口异常", e);
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 覆盖检查进度（完成后含 result）。 */
    @GetMapping("/data-health/status")
    public Map<String, Object> dataHealthStatus() {
        DataHealthService svc = dataHealthProvider.getIfAvailable();
        if (svc == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("idle", true);
            m.put("running", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return svc.status();
    }

    /**
     * 抽样对账 market_1min 中 TDX 与 MDS：条数、最新时间、重叠收盘偏差（bp）。
     * query: {@code limit} 默认 20。
     */
    @PostMapping("/data-health/mds-tdx-sample")
    public Map<String, Object> mdsTdxSample(
            @RequestParam(value = "limit", required = false) Integer limit) {
        MarketSourceSampleReconcileService svc = mdsTdxSampleProvider.getIfAvailable();
        if (svc == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return svc.sample(limit);
    }

    /**
     * 由日线重算 factor_daily。
     * <p>
     * query: {@code scope=universe|pool}（默认 universe）；或 {@code codes=600036,000001}。
     */
    @PostMapping("/factor-daily/rebuild")
    public Map<String, Object> factorDailyRebuild(
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "codes", required = false) String codes) {
        FactorDailyComputeService svc = factorDailyComputeProvider.getIfAvailable();
        if (svc == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        List<String> list = null;
        if (codes != null && !codes.trim().isEmpty()) {
            list = new ArrayList<String>();
            for (String p : codes.split(",")) {
                String c = p.trim();
                if (!c.isEmpty()) {
                    list.add(c);
                }
            }
        } else if ("pool".equalsIgnoreCase(scope)) {
            TradePoolService pool = tradePoolProvider.getIfAvailable();
            list = pool == null ? Collections.<String>emptyList() : pool.listActiveCodes();
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>(svc.rebuild(list));
        out.put("ok", true);
        out.put("scope", list == null ? "universe-with-daily" : scope == null ? "codes" : scope);
        return out;
    }

    /** TDX 灌数脚本状态（是否开启、脚本路径、是否在跑）。 */
    @GetMapping("/tdx-script/status")
    public Map<String, Object> tdxScriptStatus() {
        TdxScriptBackfillService svc = tdxScriptBackfillProvider.getIfAvailable();
        if (svc == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("enabled", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return svc.status();
    }

    /**
     * 池内 1 分钟 TDX 回填。{@code async=true}（默认）后台跑；false 同步等待。
     * 需 {@code quant.tdx-script.enabled=true}。
     */
    @PostMapping("/tdx-script/backfill-min1")
    public Map<String, Object> tdxBackfillMin1(
            @RequestParam(value = "async", required = false, defaultValue = "true") boolean async) {
        TdxScriptBackfillService svc = tdxScriptBackfillProvider.getIfAvailable();
        if (svc == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return async ? svc.backfillPoolMinuteAsync() : svc.backfillPoolMinuteSync();
    }

    /**
     * 全市场日线 TDX 回填（同步，可能很久）。{@code years} 默认 1。
     * 需 {@code quant.tdx-script.enabled=true}。
     */
    @PostMapping("/tdx-script/backfill-daily")
    public Map<String, Object> tdxBackfillDaily(
            @RequestParam(value = "years", required = false, defaultValue = "1") double years) {
        TdxScriptBackfillService svc = tdxScriptBackfillProvider.getIfAvailable();
        if (svc == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return svc.backfillDailySync(years);
    }

    /**
     * 补全回测历史 strategy_id：空白→maCross；指纹旧名→注册 id。
     * 使策略管理可关联查询旧回测。
     */
    @PostMapping("/backtest/backfill-strategy-id")
    public Map<String, Object> backfillBacktestStrategyId() {
        BacktestStrategyIdBackfillService svc = backtestStrategyIdBackfillProvider.getIfAvailable();
        if (svc == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return svc.backfill();
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
                log.error("运维接口异常", ignored);
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

    /** 分钟行情自洽最近结果（原 P0-107；现检查 market_1min） */
    @GetMapping("/data-reconcile")
    public Map<String, Object> dataReconcile() {
        return dataReconcileGateService.lastReport();
    }

    /** 立即跑一轮分钟行情自洽检查（空/滞后/稀疏/OHLC） */
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

    /** 宽睿账号登录状态（库内 active / env 回退；无密文）。 */
    @GetMapping("/kuangrui/account/status")
    public Map<String, Object> kuangruiAccountStatus() {
        KuangruiAccountLoginService s = kuangruiAccountLoginProvider.getIfAvailable();
        if (s == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("hasCred", false);
            m.put("credSource", "none");
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return s.status();
    }

    /** 查询当前生效宽睿账号（无密码；currentUsername + 来源）。 */
    @GetMapping("/kuangrui/account/current")
    public Map<String, Object> kuangruiAccountCurrent() {
        KuangruiAccountLoginService s = kuangruiAccountLoginProvider.getIfAvailable();
        if (s == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("hasCred", false);
            m.put("currentUsername", null);
            m.put("credSource", "none");
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return s.current();
    }

    /** 验柜成功后加密落库并设为 active。 */
    @PostMapping("/kuangrui/account/login")
    public Map<String, Object> kuangruiAccountLogin(@RequestBody Map<String, Object> body) {
        KuangruiAccountLoginService s = kuangruiAccountLoginProvider.getIfAvailable();
        if (s == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        Map<String, Object> b = body == null ? new LinkedHashMap<String, Object>() : body;
        return s.login(asStr(b.get("username")), asStr(b.get("password")));
    }

    /** 清除库内 active（不删历史）。 */
    @PostMapping("/kuangrui/account/logout")
    public Map<String, Object> kuangruiAccountLogout() {
        KuangruiAccountLoginService s = kuangruiAccountLoginProvider.getIfAvailable();
        if (s == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return s.logout();
    }

    /** 宽睿 OES 只读状态（默认 noop；-Pkuangrui + 开关开启后为真实客户端）。 */
    @GetMapping("/kuangrui/oes/status")
    public Map<String, Object> kuangruiOesStatus() {
        KuangruiOesOpsFacade f = kuangruiOesOpsProvider.getIfAvailable();
        if (f == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("live", false);
            m.put("hint", "需要 quant.db-enabled=true");
            return m;
        }
        return f.status();
    }

    /** OES 查资金（价÷10000 为元）。 */
    @GetMapping("/kuangrui/oes/cash")
    public Map<String, Object> kuangruiOesCash() {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.cash();
            }
        });
    }

    /** OES 查持仓。 */
    @GetMapping("/kuangrui/oes/holdings")
    public Map<String, Object> kuangruiOesHoldings() {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.holdings();
            }
        });
    }

    /** OES 查委托。 */
    @GetMapping("/kuangrui/oes/orders")
    public Map<String, Object> kuangruiOesOrders() {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.orders();
            }
        });
    }

    /** OES 查成交。 */
    @GetMapping("/kuangrui/oes/trades")
    public Map<String, Object> kuangruiOesTrades() {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.trades();
            }
        });
    }

    /** OES 资金+持仓+委托+成交快照。 */
    @GetMapping("/kuangrui/oes/snapshot")
    public Map<String, Object> kuangruiOesSnapshot() {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.snapshot();
            }
        });
    }

    /** 本地纸面账本 vs OES 柜台只读对账（不改仓）。 */
    @GetMapping("/kuangrui/oes/reconcile")
    public Map<String, Object> kuangruiOesReconcile() {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.reconcile();
            }
        });
    }

    /** 关闭 OES 客户端连接。 */
    @PostMapping("/kuangrui/oes/stop")
    public Map<String, Object> kuangruiOesStop() {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.stop();
            }
        });
    }

    /** OES 报撤能力状态（M3；order-enabled 默认关）。 */
    @GetMapping("/kuangrui/oes/order-status")
    public Map<String, Object> kuangruiOesOrderStatus() {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.orderStatus();
            }
        });
    }

    /** 联调页限价试单（须 orderLive；页面二次确认）。 */
    @PostMapping("/kuangrui/oes/place-test")
    public Map<String, Object> kuangruiOesPlaceTest(@RequestBody Map<String, Object> body) {
        final Map<String, Object> b = body == null ? new LinkedHashMap<String, Object>() : body;
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.placeTest(asStr(b.get("code")), asStr(b.get("side")),
                        asBd(b.get("price")), asInt(b.get("qty")), asStr(b.get("clientOrderId")));
            }
        });
    }

    /** 联调页撤单试单（须 orderLive；页面二次确认）。 */
    @PostMapping("/kuangrui/oes/cancel-test")
    public Map<String, Object> kuangruiOesCancelTest(@RequestBody Map<String, Object> body) {
        final Map<String, Object> b = body == null ? new LinkedHashMap<String, Object>() : body;
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.cancelTest(asInt(b.get("origClSeqNo")), asStr(b.get("code")));
            }
        });
    }

    /** M4：OES 证券产品（涨跌停/停牌/股本）。 */
    @GetMapping("/kuangrui/oes/stock")
    public Map<String, Object> kuangruiOesStock(@RequestParam(value = "code", required = false) String code) {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.stock(code);
            }
        });
    }

    /** M4：OES 交易日。 */
    @GetMapping("/kuangrui/oes/trading-day")
    public Map<String, Object> kuangruiOesTradingDay() {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.tradingDay();
            }
        });
    }

    /** M4：OES 佣金费率。 */
    @GetMapping("/kuangrui/oes/commission-rate")
    public Map<String, Object> kuangruiOesCommissionRate() {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.commissionRate();
            }
        });
    }

    /** M5+：OES 客户端总览。 */
    @GetMapping("/kuangrui/oes/client-overview")
    public Map<String, Object> kuangruiOesClientOverview() {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.clientOverview();
            }
        });
    }

    /** M5+：OES 股东账户。 */
    @GetMapping("/kuangrui/oes/inv-acct")
    public Map<String, Object> kuangruiOesInvAcct() {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.invAcct();
            }
        });
    }

    /** M5+：OES 主柜资金。 */
    @GetMapping("/kuangrui/oes/counter-cash")
    public Map<String, Object> kuangruiOesCounterCash(
            @RequestParam(value = "cashAcctId", required = false) String cashAcctId) {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.counterCash(cashAcctId);
            }
        });
    }

    /** M5+：OES 最大可买卖数量。 */
    @GetMapping("/kuangrui/oes/max-tradable-qty")
    public Map<String, Object> kuangruiOesMaxTradableQty(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "side", required = false) String side,
            @RequestParam(value = "price", required = false) BigDecimal price) {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.maxTradableQty(code, side, price);
            }
        });
    }

    /** M6：OES 银证/出入金流水。 */
    @GetMapping("/kuangrui/oes/cash-transfer-serial")
    public Map<String, Object> kuangruiOesCashTransferSerial(
            @RequestParam(value = "cashAcctId", required = false) String cashAcctId) {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.cashTransferSerial(cashAcctId);
            }
        });
    }

    /** M6：OES 银证试转（须 orderLive；页面二次确认；不改 sim 账本）。 */
    @PostMapping("/kuangrui/oes/cash-transfer-test")
    public Map<String, Object> kuangruiOesCashTransferTest(@RequestBody Map<String, Object> body) {
        final Map<String, Object> b = body == null ? new LinkedHashMap<String, Object>() : body;
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.cashTransferTest(
                        asStr(b.get("direct")),
                        asBd(b.get("amount")),
                        asStr(b.get("cashAcctId")),
                        asStr(b.get("trsfType")),
                        asStr(b.get("trdPasswd")),
                        asStr(b.get("trsfPasswd")));
            }
        });
    }

    /** M4：静态/费率门面状态。 */
    @GetMapping("/kuangrui/static/status")
    public Map<String, Object> kuangruiStaticStatus() {
        return oesOrDbOff(new OesCall() {
            @Override
            public Map<String, Object> call(KuangruiOesOpsFacade f) {
                return f.staticStatus();
            }
        });
    }

    /** M4：MDS 证券静态。 */
    @GetMapping("/kuangrui/mds/stock-static")
    public Map<String, Object> kuangruiMdsStockStatic(
            @RequestParam(value = "code", required = false) String code) {
        KuangruiMdsOpsFacade f = kuangruiMdsOpsProvider.getIfAvailable();
        if (f == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return f.stockStatic(code);
    }

    /** M4：MDS 证券状态。 */
    @GetMapping("/kuangrui/mds/security-status")
    public Map<String, Object> kuangruiMdsSecurityStatus(
            @RequestParam(value = "code", required = false) String code) {
        KuangruiMdsOpsFacade f = kuangruiMdsOpsProvider.getIfAvailable();
        if (f == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return f.securityStatus(code);
    }

    /** M4：MDS 交易时段。 */
    @GetMapping("/kuangrui/mds/session-status")
    public Map<String, Object> kuangruiMdsSessionStatus() {
        KuangruiMdsOpsFacade f = kuangruiMdsOpsProvider.getIfAvailable();
        if (f == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return f.sessionStatus();
    }

    /** M4：合并静态（MDS+OES）。 */
    @GetMapping("/kuangrui/static/stock")
    public Map<String, Object> kuangruiStaticStock(
            @RequestParam(value = "code", required = false) String code) {
        KuangruiMdsOpsFacade f = kuangruiMdsOpsProvider.getIfAvailable();
        if (f == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return f.mergedStock(code);
    }

    private Map<String, Object> oesOrDbOff(OesCall call) {
        KuangruiOesOpsFacade f = kuangruiOesOpsProvider.getIfAvailable();
        if (f == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("live", false);
            m.put("message", "需要 quant.db-enabled=true");
            return m;
        }
        return call.call(f);
    }

    private static String asStr(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static Integer asInt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return Integer.valueOf(((Number) o).intValue());
        }
        try {
            return Integer.valueOf(String.valueOf(o).trim());
        } catch (Exception e) {
            log.error("运维接口异常", e);
            return null;
        }
    }

    private static BigDecimal asBd(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal) {
            return (BigDecimal) o;
        }
        if (o instanceof Number) {
            return BigDecimal.valueOf(((Number) o).doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            log.error("运维接口异常", e);
            return null;
        }
    }

    private interface OesCall {
        Map<String, Object> call(KuangruiOesOpsFacade f);
    }
}
