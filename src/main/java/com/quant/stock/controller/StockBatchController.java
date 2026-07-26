package com.quant.stock.controller;

import com.quant.stock.backtest.BatchStockBackTestService;
import com.quant.stock.backtest.dto.BatchScanResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 批量扫描 API：对全市场（或配置宇宙）跑统一策略回测并汇总排名。
 */
@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
public class StockBatchController {

    private final BatchStockBackTestService batchStockBackTestService;

    /** 扫描全部可交易标的并返回批量回测结果列表。 */
    @GetMapping("/scanAllStock")
    public List<BatchScanResultDTO> scanAllStock() {
        return batchStockBackTestService.scanAll();
    }
}
