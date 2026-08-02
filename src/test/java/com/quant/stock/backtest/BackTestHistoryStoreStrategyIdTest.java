package com.quant.stock.backtest;

import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.backtest.dto.BtBacktestRecordDO;
import com.quant.stock.backtest.dto.SingleBacktestHistoryRecord;
import com.quant.stock.mapper.BacktestRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * strategy_id 写入与摘要映射（Task 2）。
 */
class BackTestHistoryStoreStrategyIdTest {

    private BacktestRecordMapper mapper;
    private BackTestHistoryStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        mapper = mock(BacktestRecordMapper.class);
        ObjectProvider<JdbcTemplate> jdbc = mock(ObjectProvider.class);
        when(jdbc.getIfAvailable()).thenReturn(null);
        store = new BackTestHistoryStore(jdbc);
        Field f = BackTestHistoryStore.class.getDeclaredField("backtestRecordMapper");
        f.setAccessible(true);
        f.set(store, mapper);
    }

    @Test
    void toSingle_mapsStrategyId() {
        BtBacktestRecordDO row = BtBacktestRecordDO.builder()
                .recordId("abc")
                .kind("SINGLE")
                .strategyId("maCross")
                .totalRate(new BigDecimal("0.1"))
                .build();
        when(mapper.selectSummaryByStrategyId(eq("maCross"), eq("SINGLE")))
                .thenReturn(Collections.singletonList(row));

        List<?> list = store.listSummaryByStrategy("maCross", "SINGLE");
        assertEquals(1, list.size());
        SingleBacktestHistoryRecord rec = (SingleBacktestHistoryRecord) list.get(0);
        assertEquals("maCross", rec.getStrategyId());
        assertEquals("abc", rec.getId());
    }

    @Test
    void appendSingle_writesStrategyId() {
        when(mapper.insert(any(BtBacktestRecordDO.class))).thenReturn(1);
        BackTestResult result = BackTestResult.builder()
                .stockCode("600000")
                .initCapital(new BigDecimal("100000"))
                .finalAsset(new BigDecimal("110000"))
                .totalRate(new BigDecimal("0.1"))
                .maxDrawDown(new BigDecimal("0.05"))
                .totalTradeNum(2)
                .winRate(new BigDecimal("0.5"))
                .trades(Collections.emptyList())
                .build();

        SingleBacktestHistoryRecord hist = store.appendSingle(
                "DAY", null, null, result, "maCross");
        assertNotNull(hist);
        assertEquals("maCross", hist.getStrategyId());

        ArgumentCaptor<BtBacktestRecordDO> cap = ArgumentCaptor.forClass(BtBacktestRecordDO.class);
        verify(mapper).insert(cap.capture());
        assertEquals("maCross", cap.getValue().getStrategyId());
        assertEquals("SINGLE", cap.getValue().getKind());
    }
}
