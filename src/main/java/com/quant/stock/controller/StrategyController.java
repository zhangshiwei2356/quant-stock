package com.quant.stock.controller;


import lombok.extern.slf4j.Slf4j;
import com.quant.stock.strategy.StrategyEvalService;
import com.quant.stock.strategy.StrategyPoolSeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 策略总览 API：overview（含介绍与评分）/ 按策略历史 / 单条详情 / 目标池补回测。
 */
@Slf4j
@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyEvalService strategyEvalService;
    private final StrategyPoolSeedService strategyPoolSeedService;

    /** 注册策略列表 + 聚合指标；db 未启用时 enabled=false、聚合为 0。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return strategyEvalService.overview();
    }

    /**
     * 无回测（或 force）时：目标池逐只单股回测 + 全池组合回测（异步）。
     * 进度见 {@code GET /api/strategy/seed-status}。
     */
    @PostMapping("/{id}/seed-pool-backtest")
    public Map<String, Object> seedPoolBacktest(@PathVariable("id") String id,
                                                @RequestParam(value = "force", defaultValue = "false") boolean force) {
        try {
            return strategyPoolSeedService.start(id, force);
        } catch (NoSuchElementException e) {
            log.error("策略接口异常", e);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            log.error("策略接口异常", e);
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 目标池补回测进度。 */
    @GetMapping("/seed-status")
    public Map<String, Object> seedStatus() {
        return strategyPoolSeedService.status();
    }

    /** 某策略回测摘要；未知策略 404。kind=ALL|SINGLE|PORTFOLIO。 */
    @GetMapping("/{id}/history")
    public List<Map<String, Object>> history(@PathVariable("id") String id,
                                             @RequestParam(value = "kind", defaultValue = "ALL") String kind) {
        try {
            return strategyEvalService.history(id, kind);
        } catch (NoSuchElementException e) {
            log.error("策略接口异常", e);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知策略: " + id);
        }
    }

    /** 单条回测详情（含 trades / analysis）；未知 recordId 404。 */
    @GetMapping("/history/{recordId}")
    public Map<String, Object> detail(@PathVariable("recordId") String recordId) {
        try {
            return strategyEvalService.detail(recordId);
        } catch (NoSuchElementException e) {
            log.error("策略接口异常", e);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知回测记录: " + recordId);
        }
    }
}
