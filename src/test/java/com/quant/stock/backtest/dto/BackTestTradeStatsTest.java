package com.quant.stock.backtest.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackTestTradeStatsTest {

    @Test
    void aggregatesBuySellAndPnl() {
        List<BackTradeRecord> trades = Arrays.asList(
                BackTradeRecord.builder()
                        .side("BUY").volume(100).amount(new BigDecimal("1000"))
                        .fee(new BigDecimal("0.30")).tradeTime(LocalDateTime.now()).build(),
                BackTradeRecord.builder()
                        .side("SELL").volume(100).amount(new BigDecimal("1100"))
                        .fee(new BigDecimal("1.40")).tradeTime(LocalDateTime.now()).build()
        );
        BackTestTradeStats s = BackTestTradeStats.from(trades,
                new BigDecimal("100000"), new BigDecimal("100098.30"));
        assertEquals(1, s.getBuyCount());
        assertEquals(1, s.getSellCount());
        assertEquals(1, s.getBuyLots());
        assertEquals(1, s.getSellLots());
        assertEquals(0, new BigDecimal("1000.00").compareTo(s.getBuyAmount()));
        assertEquals(0, new BigDecimal("1100.00").compareTo(s.getSellAmount()));
        assertEquals(0, new BigDecimal("1.70").compareTo(s.getTotalFee()));
        assertEquals(0, new BigDecimal("98.30").compareTo(s.getTotalPnl()));
    }
}
