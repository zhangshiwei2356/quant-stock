package com.quant.stock.strategy;

import com.quant.stock.backtest.BackTestAnalysisStore;
import com.quant.stock.backtest.BackTestHistoryStore;
import com.quant.stock.backtest.dto.BtBacktestRecordDO;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.mapper.BacktestRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 策略评估：中位数与 overview 聚合（Task 3）。
 */
class StrategyEvalServiceTest {

    private BacktestRecordMapper mapper;
    private StrategyRegistry registry;
    private QuantProperties props;
    private StrategyEvalService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        props = new QuantProperties();
        props.setDbEnabled(true);
        props.setActiveStrategy("maCross");
        MaCrossStrategy ma = new MaCrossStrategy(props);
        HoldNothingStrategy hold = new HoldNothingStrategy();
        registry = new StrategyRegistry(Arrays.asList(ma, hold), props);

        mapper = mock(BacktestRecordMapper.class);
        ObjectProvider<BacktestRecordMapper> mapperProvider = mock(ObjectProvider.class);
        when(mapperProvider.getIfAvailable()).thenReturn(mapper);

        BackTestHistoryStore historyStore = mock(BackTestHistoryStore.class);
        BackTestAnalysisStore analysisStore = mock(BackTestAnalysisStore.class);

        service = new StrategyEvalService(registry, props, mapperProvider, historyStore, analysisStore);
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void median_oddAndEven() {
        assertEquals(0, StrategyEvalService.median(Arrays.asList(
                bd("1"), bd("2"), bd("3"))).compareTo(bd("2")));
        // even: (2+3)/2 = 2.5
        assertEquals(0, StrategyEvalService.median(Arrays.asList(
                bd("1"), bd("2"), bd("3"), bd("4"))).compareTo(bd("2.5")));
    }

    @Test
    void median_empty_returnsNull() {
        assertNull(StrategyEvalService.median(Collections.<BigDecimal>emptyList()));
        assertNull(StrategyEvalService.median(null));
    }

    @Test
    void overview_ignoresNullStrategyIdRows() {
        // maCross 仅有一条 10% 收益；另有未知 strategy_id 行（mapper 不会按策略查出）
        when(mapper.selectSummaryByStrategyId(eq("maCross"), any()))
                .thenReturn(Collections.singletonList(
                        BtBacktestRecordDO.builder()
                                .recordId("s1")
                                .kind("SINGLE")
                                .strategyId("maCross")
                                .totalRate(new BigDecimal("0.10"))
                                .maxDrawdown(new BigDecimal("0.05"))
                                .savedAt(LocalDateTime.of(2026, 8, 1, 10, 0))
                                .build()));
        when(mapper.selectSummaryByStrategyId(eq("holdNothing"), any()))
                .thenReturn(Collections.<BtBacktestRecordDO>emptyList());
        when(mapper.countUnknownStrategy()).thenReturn(1L);

        Map<String, Object> overview = service.overview();
        assertEquals(true, overview.get("enabled"));
        assertEquals(1L, overview.get("unknownCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> strategies = (List<Map<String, Object>>) overview.get("strategies");
        assertNotNull(strategies);
        Map<String, Object> ma = null;
        for (Map<String, Object> row : strategies) {
            if ("maCross".equals(row.get("strategyId"))) {
                ma = row;
                break;
            }
        }
        assertNotNull(ma);
        assertEquals(1, ma.get("runCount"));
        assertEquals(0, ((BigDecimal) ma.get("avgTotalRate")).compareTo(bd("0.100000")));
        assertEquals(0, ((BigDecimal) ma.get("medianTotalRate")).compareTo(bd("0.100000")));
        // 未知行的 100% 不得混入 maCross
        assertFalse(((BigDecimal) ma.get("avgTotalRate")).compareTo(bd("1")) == 0
                || ((BigDecimal) ma.get("avgTotalRate")).compareTo(bd("0.55")) == 0);
    }
}
