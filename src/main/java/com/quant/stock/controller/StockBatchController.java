package com.quant.stock.controller;

import com.quant.stock.backtest.BatchStockBackTestService;
import com.quant.stock.backtest.dto.BatchScanResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
public class StockBatchController {

    private final BatchStockBackTestService batchStockBackTestService;

    @GetMapping("/scanAllStock")
    public List<BatchScanResultDTO> scanAllStock() {
        return batchStockBackTestService.scanAll();
    }
}
