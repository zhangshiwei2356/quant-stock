package com.quant.stock.backtest;

import com.quant.stock.mapper.BacktestAnalysisMapper;
import com.quant.stock.mapper.BacktestRecordMapper;
import com.quant.stock.mapper.StrategyParamMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下线画像清理：匹配 id、级联删分析与历史、删参数包。
 */
class RetiredMaCrossProfileCleanupServiceTest {

    @Test
    void cleanupDeletesRecordsAnalysisAndParams() {
        BacktestRecordMapper records = mock(BacktestRecordMapper.class);
        BacktestAnalysisMapper analysis = mock(BacktestAnalysisMapper.class);
        StrategyParamMapper params = mock(StrategyParamMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StrategyParamMapper> paramsProvider = mock(ObjectProvider.class);
        when(paramsProvider.getIfAvailable()).thenReturn(params);

        when(records.selectDistinctStrategyIds()).thenReturn(
                Arrays.asList("maCross", "maCrossTrend", "MACROSSSTRICT", "maCrossBalanced"));
        when(records.selectRecordIdsByStrategyIds(anyList()))
                .thenReturn(Arrays.asList("r1", "r2"));
        when(analysis.deleteByRecordIds(anyList())).thenReturn(2);
        when(records.deleteByStrategyIds(anyList())).thenReturn(2);
        when(params.deleteByStrategyId(eq("maCrossTrend"))).thenReturn(1);
        when(params.deleteByStrategyId(eq("maCrossVolume"))).thenReturn(0);
        when(params.deleteByStrategyId(eq("maCrossStrict"))).thenReturn(0);
        when(params.deleteByStrategyId(eq("MACROSSSTRICT"))).thenReturn(1);

        RetiredMaCrossProfileCleanupService svc =
                new RetiredMaCrossProfileCleanupService(records, analysis, paramsProvider);
        Map<String, Object> out = svc.cleanup();

        assertTrue(Boolean.TRUE.equals(out.get("ok")));
        @SuppressWarnings("unchecked")
        List<String> matched = (List<String>) out.get("matchedStrategyIds");
        assertTrue(matched.contains("maCrossTrend"));
        assertTrue(matched.contains("MACROSSSTRICT"));
        assertTrue(matched.contains("maCrossVolume"));
        assertEquals(2, out.get("analysisDeleted"));
        assertEquals(2, out.get("recordsDeleted"));
        verify(analysis).deleteByRecordIds(Arrays.asList("r1", "r2"));
        verify(records).deleteByStrategyIds(anyList());
    }

    @Test
    void retiredIdsConstantCoversThreeProfiles() {
        assertEquals(3, RetiredMaCrossProfileCleanupService.RETIRED_IDS.size());
        assertTrue(RetiredMaCrossProfileCleanupService.RETIRED_IDS.contains("macrosstrend"));
        assertTrue(RetiredMaCrossProfileCleanupService.RETIRED_IDS.contains("macrossvolume"));
        assertTrue(RetiredMaCrossProfileCleanupService.RETIRED_IDS.contains("macrossstrict"));
    }

    @Test
    void emptyDistinctStillTargetsCanonicalIds() {
        BacktestRecordMapper records = mock(BacktestRecordMapper.class);
        BacktestAnalysisMapper analysis = mock(BacktestAnalysisMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StrategyParamMapper> paramsProvider = mock(ObjectProvider.class);
        when(paramsProvider.getIfAvailable()).thenReturn(null);
        when(records.selectDistinctStrategyIds()).thenReturn(Collections.<String>emptyList());
        when(records.selectRecordIdsByStrategyIds(anyList())).thenReturn(Collections.<String>emptyList());
        when(records.deleteByStrategyIds(anyList())).thenReturn(0);

        RetiredMaCrossProfileCleanupService svc =
                new RetiredMaCrossProfileCleanupService(records, analysis, paramsProvider);
        Map<String, Object> out = svc.cleanup();
        assertEquals(0, out.get("recordsDeleted"));
        @SuppressWarnings("unchecked")
        List<String> matched = (List<String>) out.get("matchedStrategyIds");
        assertEquals(3, matched.size());
    }
}
