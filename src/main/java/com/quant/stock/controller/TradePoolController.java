package com.quant.stock.controller;

import com.quant.stock.pool.TradePoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 唯一目标池 API：盘后扫描自动覆盖 {@code trade_pool}；人工可移出；无确认/忽略。
 */
@RestController
@RequestMapping("/api/stock")
@RequiredArgsConstructor
public class TradePoolController {

    private final ObjectProvider<TradePoolService> tradePoolServiceProvider;

    /** 全市场（浏览用） */
    @GetMapping("/universe")
    public List<Map<String, String>> universe() {
        TradePoolService svc = tradePoolServiceProvider.getIfAvailable();
        if (svc == null) {
            return Collections.emptyList();
        }
        return svc.listUniverse();
    }

    /** 唯一目标池总览 */
    @GetMapping("/trade-pool")
    public Map<String, Object> tradePool() {
        return require().overview();
    }

    /** 兼容别名：与 {@link #tradePool()} 相同，返回当前活跃目标池 */
    @GetMapping("/trade-pool/final")
    public Map<String, Object> finalPool() {
        return require().overview();
    }

    /** 单只目标池分析报告（行展开；优先读库） */
    @GetMapping("/trade-pool/{code}/analysis")
    public Map<String, Object> analysis(@PathVariable("code") String code) {
        try {
            return require().analysis(code);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 按报告 id 读入选分析 */
    @GetMapping("/trade-pool/report/{id}")
    public Map<String, Object> reportById(@PathVariable("id") Long id) {
        try {
            return require().reportById(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 扫描全市场 → 覆盖唯一目标池 */
    @PostMapping("/trade-pool/rebuild")
    public Map<String, Object> rebuild() {
        return require().rebuildFromUniverse();
    }

    /** 全市场分析打分 + 覆盖目标池 + 落盘 Markdown 报告 */
    @PostMapping("/trade-pool/analyze")
    public Map<String, Object> analyze() {
        return require().analyzeAndRecommend();
    }

    /** 从目标池移出（不停仓/不卖出） */
    @PostMapping("/trade-pool/{code}/remove")
    public Map<String, Object> remove(@PathVariable("code") String code) {
        try {
            return require().removeFromPool(code);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private TradePoolService require() {
        TradePoolService svc = tradePoolServiceProvider.getIfAvailable();
        if (svc == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "需要 quant.db-enabled=true");
        }
        return svc;
    }
}
