package com.quant.stock.backtest;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.mapper.BacktestAnalysisMapper;
import com.quant.stock.mapper.BacktestRecordMapper;
import com.quant.stock.mapper.StrategyParamMapper;
import com.quant.stock.trade.LiveLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下线画像清理：匹配注册 id/指纹类名、级联删、重置死激活配置。
 */
class RetiredMaCrossProfileCleanupServiceTest {

    @Test
    void cleanupDeletesRecordsAnalysisAndParams() {
        BacktestRecordMapper records = mock(BacktestRecordMapper.class);
        BacktestAnalysisMapper analysis = mock(BacktestAnalysisMapper.class);
        StrategyParamMapper params = mock(StrategyParamMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StrategyParamMapper> paramsProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LiveLedgerService> ledgerProvider = mock(ObjectProvider.class);
        when(paramsProvider.getIfAvailable()).thenReturn(params);
        when(ledgerProvider.getIfAvailable()).thenReturn(null);

        when(records.selectDistinctStrategyIds()).thenReturn(
                Arrays.asList("maCross", "maCrossTrend", "MACROSSSTRICT", "MaCrossVolumeStrategy", "maCrossBalanced"));
        when(records.selectRecordIdsByStrategyIds(anyList()))
                .thenReturn(Arrays.asList("r1", "r2"));
        when(analysis.deleteByRecordIds(anyList())).thenReturn(2);
        when(records.deleteByStrategyIds(anyList())).thenReturn(2);
        when(params.deleteByStrategyId(anyString())).thenReturn(0);
        when(params.deleteByStrategyId(eq("maCrossTrend"))).thenReturn(1);

        QuantProperties props = new QuantProperties();
        props.setActiveStrategy("maCross");
        RetiredMaCrossProfileCleanupService svc =
                new RetiredMaCrossProfileCleanupService(records, analysis, paramsProvider, ledgerProvider, props);
        Map<String, Object> out = svc.cleanup();

        assertTrue(Boolean.TRUE.equals(out.get("ok")));
        @SuppressWarnings("unchecked")
        List<String> matched = (List<String>) out.get("matchedStrategyIds");
        assertTrue(matched.contains("maCrossTrend"));
        assertTrue(matched.contains("MACROSSSTRICT"));
        assertTrue(matched.contains("MaCrossVolumeStrategy"));
        assertEquals(2, out.get("analysisDeleted"));
        assertEquals(2, out.get("recordsDeleted"));
        assertFalse(Boolean.TRUE.equals(out.get("activeStrategyReset")));
        verify(analysis).deleteByRecordIds(Arrays.asList("r1", "r2"));
        verify(records).deleteByStrategyIds(anyList());
    }

    @Test
    void retiredIdsCoverRegisterAndFingerprintNames() {
        assertEquals(6, RetiredMaCrossProfileCleanupService.RETIRED_IDS.size());
        assertTrue(RetiredMaCrossProfileCleanupService.isRetired("maCrossTrend"));
        assertTrue(RetiredMaCrossProfileCleanupService.isRetired("MaCrossStrictStrategy"));
        assertFalse(RetiredMaCrossProfileCleanupService.isRetired("maCrossBalanced"));
        assertFalse(RetiredMaCrossProfileCleanupService.isRetired("maCross"));
    }

    @Test
    void resetsRetiredActiveStrategyInMemoryAndConfig() {
        BacktestRecordMapper records = mock(BacktestRecordMapper.class);
        BacktestAnalysisMapper analysis = mock(BacktestAnalysisMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StrategyParamMapper> paramsProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LiveLedgerService> ledgerProvider = mock(ObjectProvider.class);
        LiveLedgerService ledger = mock(LiveLedgerService.class);
        when(paramsProvider.getIfAvailable()).thenReturn(null);
        when(ledgerProvider.getIfAvailable()).thenReturn(ledger);
        when(records.selectDistinctStrategyIds()).thenReturn(Collections.<String>emptyList());
        when(records.selectRecordIdsByStrategyIds(anyList())).thenReturn(Collections.<String>emptyList());
        when(records.deleteByStrategyIds(anyList())).thenReturn(0);
        when(ledger.loadConfigOrNull(eq("quant.active-strategy"))).thenReturn("maCrossVolume");

        QuantProperties props = new QuantProperties();
        props.setActiveStrategy("maCrossStrict");
        RetiredMaCrossProfileCleanupService svc =
                new RetiredMaCrossProfileCleanupService(records, analysis, paramsProvider, ledgerProvider, props);
        Map<String, Object> out = svc.cleanup();

        assertTrue(Boolean.TRUE.equals(out.get("activeStrategyReset")));
        assertEquals("maCross", props.getActiveStrategy());
        verify(ledger).saveConfig(eq("quant.active-strategy"), eq("maCross"), anyString());
    }
}
