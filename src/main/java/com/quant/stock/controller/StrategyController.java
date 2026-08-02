package com.quant.stock.controller;

import com.quant.stock.strategy.StrategyEvalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 策略评估 API：overview / 按策略历史 / 单条详情（内嵌 analysis）。
 */
@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyEvalService strategyEvalService;

    /** 注册策略列表 + 聚合指标；db 未启用时 enabled=false、聚合为 0。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return strategyEvalService.overview();
    }

    /** 某策略回测摘要；未知策略 404。kind=ALL|SINGLE|PORTFOLIO。 */
    @GetMapping("/{id}/history")
    public List<Map<String, Object>> history(@PathVariable("id") String id,
                                             @RequestParam(value = "kind", defaultValue = "ALL") String kind) {
        try {
            return strategyEvalService.history(id, kind);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知策略: " + id);
        }
    }

    /** 单条回测详情（含 trades / analysis）；未知 recordId 404。 */
    @GetMapping("/history/{recordId}")
    public Map<String, Object> detail(@PathVariable("recordId") String recordId) {
        try {
            return strategyEvalService.detail(recordId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知回测记录: " + recordId);
        }
    }
}
