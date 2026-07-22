package com.quant.stock.controller;

import com.quant.stock.account.AccountOverviewService;
import com.quant.stock.task.StrategyTask;
import com.quant.stock.trade.dto.OrderDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账户概览：资金权益 / 持仓 / 委托 / 权益日结 / 风控事件（本地模拟账本只读）。
 * 另提供 sdk 撤单 / 部成本地桩接口。
 */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountOverviewService accountOverviewService;
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

    @GetMapping("/cashflows")
    public Map<String, Object> cashflows(@RequestParam(defaultValue = "120") int limit) {
        return accountOverviewService.cashflows(limit);
    }

    @GetMapping("/risk-logs")
    public Map<String, Object> riskLogs(@RequestParam(defaultValue = "100") int limit) {
        return accountOverviewService.riskLogs(limit);
    }
}
