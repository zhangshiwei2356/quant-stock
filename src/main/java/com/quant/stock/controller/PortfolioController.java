package com.quant.stock.controller;

import com.quant.stock.backtest.BackTestAnalysisStore;
import com.quant.stock.backtest.BackTestHistoryStore;
import com.quant.stock.backtest.PortfolioBackTestEngine;
import com.quant.stock.backtest.dto.BackTestAnalysisRecord;
import com.quant.stock.backtest.dto.BackTestQueryDTO;
import com.quant.stock.backtest.dto.PortfolioBacktestHistoryRecord;
import com.quant.stock.backtest.dto.PortfolioResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioBackTestEngine portfolioBackTestEngine;
    private final BackTestHistoryStore backTestHistoryStore;
    private final BackTestAnalysisStore backTestAnalysisStore;

    @PostMapping("/run")
    public PortfolioResultDTO run(@RequestBody BackTestQueryDTO query) {
        PortfolioResultDTO result = portfolioBackTestEngine.run(query);
        PortfolioBacktestHistoryRecord hist = backTestHistoryStore.appendPortfolio(query, result);
        if (hist != null) {
            backTestAnalysisStore.appendPortfolio(hist.getId(), hist.getSavedAt(), query, result);
        }
        return result;
    }

    @GetMapping("/history")
    public List<PortfolioBacktestHistoryRecord> history() {
        return backTestHistoryStore.listPortfolio();
    }

    /** 清除全部组合回测记录（同时清除对应分析） */
    @DeleteMapping("/history")
    public Map<String, Object> clearHistory() {
        int removed = backTestHistoryStore.clearAllPortfolio();
        int analysisRemoved = backTestAnalysisStore.clearAllPortfolio();
        Map<String, Object> resp = new HashMap<String, Object>();
        resp.put("removed", removed);
        resp.put("analysisRemoved", analysisRemoved);
        return resp;
    }

    /** 组合分析：传 id 查与历史一一对应的一条；不传则列出全部 */
    @GetMapping("/analysis")
    public Object analysis(@RequestParam(value = "id", required = false) String id) {
        if (id != null && !id.trim().isEmpty()) {
            BackTestAnalysisRecord one = backTestAnalysisStore.getPortfolioById(id);
            return one == null ? new HashMap<String, Object>() : one;
        }
        return backTestAnalysisStore.listPortfolio();
    }
}
