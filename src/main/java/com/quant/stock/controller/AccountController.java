package com.quant.stock.controller;

import com.quant.stock.account.AccountOverviewService;
import com.quant.stock.account.LiveCorrelationService;
import com.quant.stock.account.PaperLiveReconcileService;
import com.quant.stock.account.PartialFillReportService;
import com.quant.stock.account.SlippageResidualService;
import com.quant.stock.risk.RiskAlertService;
import com.quant.stock.risk.SignalDriftMonitor;
import com.quant.stock.risk.StrategyRetirementService;
import com.quant.stock.risk.StressScenarioService;
import com.quant.stock.risk.StructuralBreakMonitor;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.risk.IcDecayMonitor;
import com.quant.stock.risk.LimitPriceProtect;
import com.quant.stock.risk.ShortSellPolicy;
import com.quant.stock.risk.TurnoverGuardService;
import com.quant.stock.trade.CapacityThrottle;
import com.quant.stock.task.StrategyTask;
import com.quant.stock.trade.dto.OrderDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账户概览：资金权益 / 持仓 / 委托 / 权益日结 / 风控事件（本地模拟账本只读）。
 * 另提供 sdk 撤单 / 部成本地桩接口、纸面-实盘差异对账。
 */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountOverviewService accountOverviewService;
    private final PaperLiveReconcileService paperLiveReconcileService;
    private final StrategyRetirementService strategyRetirementService;
    private final LiveCorrelationService liveCorrelationService;
    private final RiskAlertService riskAlertService;
    private final SlippageResidualService slippageResidualService;
    private final PartialFillReportService partialFillReportService;
    private final StressScenarioService stressScenarioService;
    private final SignalDriftMonitor signalDriftMonitor;
    private final StructuralBreakMonitor structuralBreakMonitor;
    private final TurnoverGuardService turnoverGuardService;
    private final IcDecayMonitor icDecayMonitor;
    private final QuantProperties quantProperties;
    private final StrategyTask strategyTask;

    @GetMapping
    public Map<String, Object> overview() {
        return accountOverviewService.overview();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return accountOverviewService.summary();
    }

    @GetMapping("/positions")
    public Map<String, Object> positions() {
        List<Map<String, Object>> items = accountOverviewService.positions();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("count", items.size());
        m.put("items", items);
        m.putAll(accountOverviewService.summary());
        return m;
    }

    @GetMapping("/orders")
    public Map<String, Object> orders() {
        List<Map<String, Object>> items = accountOverviewService.orders();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("count", items.size());
        m.put("items", items);
        return m;
    }

    /** 撤销 SUBMITTED/PARTIAL 委托（释放预留资金/可卖量） */
    @PostMapping("/orders/{orderId}/cancel")
    public Map<String, Object> cancelOrder(@PathVariable String orderId) {
        OrderDTO order = strategyTask.cancelOrder(orderId);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", order != null);
        m.put("orderId", orderId);
        m.put("status", order == null ? null : order.getStatus().name());
        m.put("filledVolume", order == null ? null : order.getFilledVolume());
        m.put("message", order == null ? "撤单失败（不存在或不可撤）" : "已撤销");
        return m;
    }

    /** 本地部成桩：追加成交量（100 股整数倍） */
    @PostMapping("/orders/{orderId}/partial-fill")
    public Map<String, Object> partialFill(@PathVariable String orderId,
                                           @RequestParam int qty) {
        OrderDTO order = strategyTask.applyPartialFill(orderId, qty);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", order != null);
        m.put("orderId", orderId);
        m.put("status", order == null ? null : order.getStatus().name());
        m.put("filledVolume", order == null ? null : order.getFilledVolume());
        m.put("message", order == null ? "部成失败" : "已部成/成交");
        return m;
    }

    /** 改价=撤补重置队尾（P0-95）：新 orderId，不保队列优先级 */
    @PostMapping("/orders/{orderId}/replace")
    public Map<String, Object> replaceOrder(@PathVariable String orderId,
                                            @RequestParam BigDecimal price,
                                            @RequestParam(required = false) Integer volume) {
        OrderDTO order = strategyTask.replaceOrder(orderId, price, volume);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", order != null && order.getStatus() != OrderDTO.Status.REJECTED);
        m.put("oldOrderId", orderId);
        m.put("orderId", order == null ? null : order.getOrderId());
        m.put("status", order == null ? null : order.getStatus().name());
        m.put("price", order == null ? null : order.getPrice());
        m.put("volume", order == null ? null : order.getVolume());
        m.put("message", order == null ? "改价失败（不存在或不可撤）" : "已撤补（队尾重置）");
        return m;
    }

    @GetMapping("/cashflows")
    public Map<String, Object> cashflows(@RequestParam(defaultValue = "120") int limit) {
        return accountOverviewService.cashflows(limit);
    }

    @GetMapping("/risk-logs")
    public Map<String, Object> riskLogs(@RequestParam(defaultValue = "100") int limit) {
        return accountOverviewService.riskLogs(limit);
    }

    /**
     * 纸面-实盘差异对账（P0-91）：闪烁/成本残差/选股漂移/撮合假设/模式闸门。
     */
    @GetMapping("/paper-live-gap")
    public Map<String, Object> paperLiveGap() {
        return paperLiveReconcileService.report();
    }

    /** 策略退役状态（P0-92） */
    @GetMapping("/retirement")
    public Map<String, Object> retirement() {
        return strategyRetirementService.status(LocalDate.now());
    }

    /** 手动退役（禁新开） */
    @PostMapping("/retirement/retire")
    public Map<String, Object> retire(@RequestParam(defaultValue = "MANUAL") String reason,
                                      @RequestParam(required = false) String note) {
        return strategyRetirementService.retire(LocalDate.now(), reason, note);
    }

    /** 冷却满后恢复；force=true 需双人复核（先武装令牌，再带 confirmCode） */
    @PostMapping("/retirement/resume")
    public Map<String, Object> resume(@RequestParam(defaultValue = "false") boolean force,
                                      @RequestParam(required = false) String confirmCode) {
        return strategyRetirementService.resume(LocalDate.now(), force, confirmCode);
    }

    /** 当前持仓两两收益相关（P0-105，只告警） */
    @GetMapping("/correlation")
    public Map<String, Object> correlation() {
        return liveCorrelationService.report();
    }

    /** 风控告警分级环形缓冲（P0-97） */
    @GetMapping("/alerts")
    public Map<String, Object> alerts(@RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> m = riskAlertService.snapshot();
        m.put("recent", riskAlertService.recent(limit));
        return m;
    }

    /** 滑点/费用残差日报（P0-99，不回写改价） */
    @GetMapping("/slippage-residual")
    public Map<String, Object> slippageResidual() {
        return slippageResidualService.dailyReport();
    }

    /** 部成率日报（P0-95） */
    @GetMapping("/partial-fill")
    public Map<String, Object> partialFillReport() {
        return partialFillReportService.dailyReport();
    }

    /** 预注册压力情景状态（P0-96） */
    @GetMapping("/stress")
    public Map<String, Object> stress() {
        return stressScenarioService.catalogAndStatus();
    }

    /** 信号漂移监控（P0-90） */
    @GetMapping("/signal-drift")
    public Map<String, Object> signalDrift() {
        return signalDriftMonitor.status();
    }

    /** 结构突变监控（P0-120） */
    @GetMapping("/structural-break")
    public Map<String, Object> structuralBreak() {
        return structuralBreakMonitor.status();
    }

    /** 换手门禁状态（P0-104） */
    @GetMapping("/turnover")
    public Map<String, Object> turnover() {
        return turnoverGuardService.status(strategyTask.getMarkEquity());
    }

    /** IC 衰减监控（P0-125）：半衰期/IR；只降仓 */
    @GetMapping("/ic-decay")
    public Map<String, Object> icDecay() {
        return icDecayMonitor.status();
    }

    /** 禁空头边界（P0-102）：多头现货；无融券开关 */
    @GetMapping("/short-policy")
    public Map<String, Object> shortPolicy() {
        return ShortSellPolicy.status();
    }

    /** 限价保护边界（P0-94）：涨跌停夹紧；五档/L2 不可用 */
    @GetMapping("/order-protect")
    public Map<String, Object> orderProtect() {
        return LimitPriceProtect.status(quantProperties);
    }

    /** 扩容/POV 执行边界（P0-112）：无 TWAP */
    @GetMapping("/execution-cap")
    public Map<String, Object> executionCap() {
        return CapacityThrottle.status(quantProperties, strategyTask.getMarkEquity());
    }
}
