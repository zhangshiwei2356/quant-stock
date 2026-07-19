package com.quant.stock.controller;

import com.quant.stock.account.AccountOverviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账户概览：资金权益 / 持仓 / 委托 / 权益日结 / 风控事件（本地模拟账本只读）。
 */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountOverviewService accountOverviewService;

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

    @GetMapping("/cashflows")
    public Map<String, Object> cashflows(@RequestParam(defaultValue = "120") int limit) {
        return accountOverviewService.cashflows(limit);
    }

    @GetMapping("/risk-logs")
    public Map<String, Object> riskLogs(@RequestParam(defaultValue = "100") int limit) {
        return accountOverviewService.riskLogs(limit);
    }
}
