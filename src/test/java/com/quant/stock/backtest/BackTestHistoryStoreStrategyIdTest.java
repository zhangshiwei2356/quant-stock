package com.quant.stock.backtest;

import com.quant.stock.backtest.dto.BackTestQueryDTO;
import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.backtest.dto.BtBacktestRecordDO;
import com.quant.stock.backtest.dto.PortfolioBacktestHistoryRecord;
import com.quant.stock.backtest.dto.PortfolioResultDTO;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
                .equityCurve(java.util.Arrays.asList(
                        new BigDecimal("100000"),
                        new BigDecimal("102000"),
                        new BigDecimal("101000"),
                        new BigDecimal("105000"),
                        new BigDecimal("110000")))
                .build();

        SingleBacktestHistoryRecord hist = store.appendSingle(
                "DAY", null, null, result, "maCross");
        assertNotNull(hist);
        assertEquals("maCross", hist.getStrategyId());
        assertNotNull(hist.getSharpe());

        ArgumentCaptor<BtBacktestRecordDO> cap = ArgumentCaptor.forClass(BtBacktestRecordDO.class);
        verify(mapper).insert(cap.capture());
        assertEquals("maCross", cap.getValue().getStrategyId());
        assertEquals("SINGLE", cap.getValue().getKind());
        assertNotNull(cap.getValue().getSharpe());
        assertEquals(0, hist.getSharpe().compareTo(cap.getValue().getSharpe()));
    }

    @Test
    void toPortfolio_mapsStrategyId() {
        BtBacktestRecordDO row = BtBacktestRecordDO.builder()
                .recordId("pf1")
                .kind("PORTFOLIO")
                .strategyId("maCross")
                .totalRate(new BigDecimal("0.2"))
                .build();
        when(mapper.selectSummaryByStrategyId(eq("maCross"), eq("PORTFOLIO")))
                .thenReturn(Collections.singletonList(row));

        List<?> list = store.listSummaryByStrategy("maCross", "PORTFOLIO");
        assertEquals(1, list.size());
        PortfolioBacktestHistoryRecord rec = (PortfolioBacktestHistoryRecord) list.get(0);
        assertEquals("maCross", rec.getStrategyId());
        assertEquals("pf1", rec.getId());
    }

    @Test
    void appendPortfolio_writesStrategyId() {
        when(mapper.insert(any(BtBacktestRecordDO.class))).thenReturn(1);
        PortfolioResultDTO result = PortfolioResultDTO.builder()
                .initCapital(new BigDecimal("200000"))
                .finalAsset(new BigDecimal("220000"))
                .totalRate(new BigDecimal("0.1"))
                .maxDrawDown(new BigDecimal("0.04"))
                .totalTradeNum(3)
                .winRate(new BigDecimal("0.6"))
                .trades(Collections.emptyList())
                .stockResults(Collections.emptyList())
                .build();
        BackTestQueryDTO query = BackTestQueryDTO.builder()
                .stockCodeList(Collections.singletonList("600000"))
                .build();

        PortfolioBacktestHistoryRecord hist = store.appendPortfolio(query, result, "maCross");
        assertNotNull(hist);
        assertEquals("maCross", hist.getStrategyId());

        ArgumentCaptor<BtBacktestRecordDO> cap = ArgumentCaptor.forClass(BtBacktestRecordDO.class);
        verify(mapper).insert(cap.capture());
        assertEquals("maCross", cap.getValue().getStrategyId());
        assertEquals("PORTFOLIO", cap.getValue().getKind());
    }

    @Test
    void getByRecordId_nullMapper_returnsNull() throws Exception {
        Field f = BackTestHistoryStore.class.getDeclaredField("backtestRecordMapper");
        f.setAccessible(true);
        f.set(store, null);

        assertNull(store.getByRecordId("any-id"));
    }

    @Test
    void getByRecordId_emptyRow_returnsNull() {
        when(mapper.selectByRecordId(eq("missing"))).thenReturn(null);

        assertNull(store.getByRecordId("missing"));
    }

    @Test
    void getByRecordId_singleRow_returnsSingleBacktestHistoryRecord() {
        BtBacktestRecordDO row = BtBacktestRecordDO.builder()
                .recordId("s1")
                .kind("SINGLE")
                .strategyId("maCross")
                .stockCode("600000")
                .totalRate(new BigDecimal("0.1"))
                .build();
        when(mapper.selectByRecordId(eq("s1"))).thenReturn(row);

        Object got = store.getByRecordId("s1");
        assertTrue(got instanceof SingleBacktestHistoryRecord);
        SingleBacktestHistoryRecord rec = (SingleBacktestHistoryRecord) got;
        assertEquals("s1", rec.getId());
        assertEquals("maCross", rec.getStrategyId());
        assertEquals("600000", rec.getStockCode());
    }

    @Test
    void getByRecordId_portfolioRow_returnsPortfolioBacktestHistoryRecord() {
        BtBacktestRecordDO row = BtBacktestRecordDO.builder()
                .recordId("p1")
                .kind("PORTFOLIO")
                .strategyId("maCross")
                .stockCodesJson("[\"600000\",\"000001\"]")
                .totalRate(new BigDecimal("0.15"))
                .build();
        when(mapper.selectByRecordId(eq("p1"))).thenReturn(row);

        Object got = store.getByRecordId("p1");
        assertTrue(got instanceof PortfolioBacktestHistoryRecord);
        PortfolioBacktestHistoryRecord rec = (PortfolioBacktestHistoryRecord) got;
        assertEquals("p1", rec.getId());
        assertEquals("maCross", rec.getStrategyId());
        assertEquals(2, rec.getStockCodeList().size());
    }
}
