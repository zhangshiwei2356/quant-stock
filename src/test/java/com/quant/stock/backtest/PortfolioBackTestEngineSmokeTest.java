package com.quant.stock.backtest;

import com.quant.stock.admin.EffectiveParamsService;
import com.quant.stock.admin.QuantPropertiesCopy;
import com.quant.stock.backtest.dto.BackTestQueryDTO;
import com.quant.stock.backtest.dto.PortfolioResultDTO;
import com.quant.stock.calendar.TradingCalendar;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.BarPeriod;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.risk.OpenFilterService;
import com.quant.stock.session.BranchScaffoldStrategy;
import com.quant.stock.session.SessionBackTestEngine;
import com.quant.stock.session.SessionDepProbe;
import com.quant.stock.strategy.HoldNothingStrategy;
import com.quant.stock.strategy.MaCrossStrategy;
import com.quant.stock.strategy.StrategyRegistry;
import com.quant.stock.trade.TradeCostModel;
import com.quant.stock.util.PositionAmountUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 组合回测冒烟：经典共享资金池 + session 等权分账旁路。
 */
class PortfolioBackTestEngineSmokeTest {

    @Test
    void runTwoSyntheticNamesReturnsAtrRiskAndFingerprint() {
        QuantProperties props = new QuantProperties();
        props.setQuietOpenEnabled(false);
        props.setQuietCloseEnabled(false);
        props.setMarketCapFilterEnabled(false);
        props.setMinAvgVolume20(1L);
        props.setStopLossEnabled(true);
        props.setFeeRate(new BigDecimal("0.0003"));

        MarketDataService mds = mock(MarketDataService.class);
        when(mds.getKline(eq("600036"), eq(BarPeriod.DAY), any(), any()))
                .thenReturn(syntheticUptrendDays("600036", 120));
        when(mds.getKline(eq("600519"), eq(BarPeriod.DAY), any(), any()))
                .thenReturn(syntheticUptrendDays("600519", 120));

        PortfolioBackTestEngine engine = engine(props, mds);
        BackTestQueryDTO q = BackTestQueryDTO.builder()
                .stockCodeList(Arrays.asList("600036", "600519"))
                .initCapital(new BigDecimal("100000"))
                .build();
        PortfolioResultDTO result = engine.run(q);
        assertNotNull(result);
        assertEquals("classic", result.getEngine());
        assertNotNull(result.getFinalAsset());
        assertTrue(result.getFinalAsset().compareTo(BigDecimal.ZERO) > 0);
        assertNotNull(result.getConfigFingerprint());
        assertTrue(result.getConfigFingerprint().startsWith("v1:"));
        assertNotNull(result.getAtrRisk());
        assertTrue(result.getAtrRisk().containsKey("atrStopMultiplier"));
        assertNotNull(result.getCorrelation());
    }

    @Test
    void sessionEqualSplitAggregatesTwoLegs() {
        QuantProperties props = new QuantProperties();
        props.setLimitPriceProtectEnabled(false);
        props.setMaxParticipationAdv(BigDecimal.ZERO);
        MarketDataService mds = mock(MarketDataService.class);
        when(mds.getKline(eq("600036"), eq(BarPeriod.MIN_1), any(), any()))
                .thenReturn(syntheticSessionMinutes("600036", 3));
        when(mds.getKline(eq("600519"), eq(BarPeriod.MIN_1), any(), any()))
                .thenReturn(syntheticSessionMinutes("600519", 3));

        PortfolioBackTestEngine engine = engine(props, mds);
        BackTestQueryDTO q = BackTestQueryDTO.builder()
                .stockCodeList(Arrays.asList("600036", "600519"))
                .initCapital(new BigDecimal("100000"))
                .strategyId(BranchScaffoldStrategy.ID)
                .engine("session")
                .build();
        PortfolioResultDTO result = engine.run(q);
        assertEquals("session", result.getEngine());
        assertNotNull(result.getStockResults());
        assertEquals(2, result.getStockResults().size());
        assertNotNull(result.getSessionBranchStats());
        assertEquals("EQUAL_SPLIT_SESSION", result.getSessionBranchStats().get("mode"));
        assertTrue(result.getConfigFingerprint().contains("pfSess"));
        assertNotNull(result.getAnalysisSummary());
        assertTrue(result.getAnalysisSummary().contains("equalSplit"));
    }

    @Test
    void resolveEngineDefaultsBranchScaffoldToSession() {
        QuantProperties props = new QuantProperties();
        assertEquals("session", PortfolioBackTestEngine.resolveEngine(
                BackTestQueryDTO.builder().build(), new BranchScaffoldStrategy()));
        assertEquals("classic", PortfolioBackTestEngine.resolveEngine(
                BackTestQueryDTO.builder().build(), new MaCrossStrategy(props)));
        assertEquals("classic", PortfolioBackTestEngine.resolveEngine(
                BackTestQueryDTO.builder().engine("classic").build(), new BranchScaffoldStrategy()));
    }

    private static PortfolioBackTestEngine engine(QuantProperties props, MarketDataService mds) {
        EffectiveParamsService eps = mock(EffectiveParamsService.class);
        when(eps.resolve(anyString())).thenAnswer(inv -> QuantPropertiesCopy.copy(props));
        when(eps.resolve(anyString(), any())).thenAnswer(inv -> QuantPropertiesCopy.copy(props));
        when(eps.hasSparse(anyString())).thenReturn(false);
        SessionBackTestEngine sessionEngine = new SessionBackTestEngine(
                mds, new SessionDepProbe(mds), props, new TradeCostModel(props), new PositionAmountUtil(props));
        return new PortfolioBackTestEngine(
                props,
                eps,
                mds,
                new PositionAmountUtil(props),
                new TradeCostModel(props),
                new OpenFilterService(props),
                new StrategyRegistry(Arrays.asList(
                        new MaCrossStrategy(props),
                        new HoldNothingStrategy(),
                        new BranchScaffoldStrategy()), props),
                new TradingCalendar(),
                sessionEngine);
    }

    private static List<BarDTO> syntheticUptrendDays(String code, int days) {
        List<BarDTO> list = new ArrayList<BarDTO>();
        BigDecimal price = new BigDecimal("10.00");
        LocalDate day = LocalDate.of(2025, 1, 2);
        int made = 0;
        while (made < days) {
            if (day.getDayOfWeek().getValue() <= 5) {
                BigDecimal open = price;
                BigDecimal close = price.multiply(new BigDecimal("1.008")).setScale(2, RoundingMode.HALF_UP);
                BigDecimal high = close.max(open).multiply(new BigDecimal("1.005")).setScale(2, RoundingMode.HALF_UP);
                BigDecimal low = open.min(close).multiply(new BigDecimal("0.995")).setScale(2, RoundingMode.HALF_UP);
                list.add(BarDTO.builder()
                        .code(code)
                        .barBegin(LocalDateTime.of(day, LocalTime.of(9, 30)))
                        .open(open).high(high).low(low).close(close)
                        .volume(new BigDecimal("1000000"))
                        .build());
                price = close;
                made++;
            }
            day = day.plusDays(1);
        }
        return list;
    }

    private static List<BarDTO> syntheticSessionMinutes(String code, int days) {
        List<BarDTO> out = new ArrayList<BarDTO>();
        LocalDate d = LocalDate.of(2026, 3, 2);
        BigDecimal px = new BigDecimal("10");
        for (int day = 0; day < days; day++) {
            LocalDate cur = d.plusDays(day);
            addMin(out, code, cur, LocalTime.of(9, 30), px);
            addMin(out, code, cur, LocalTime.of(9, 45), px);
            addMin(out, code, cur, LocalTime.of(10, 30), px);
            addMin(out, code, cur, LocalTime.of(14, 0), px);
            addMin(out, code, cur, LocalTime.of(14, 45), px);
            addMin(out, code, cur, LocalTime.of(14, 59), px);
            px = px.add(new BigDecimal("0.02"));
        }
        while (out.size() < 30) {
            addMin(out, code, d.plusDays(days), LocalTime.of(10, out.size() % 30), px);
        }
        return out;
    }

    private static void addMin(List<BarDTO> out, String code, LocalDate day, LocalTime t, BigDecimal px) {
        out.add(BarDTO.builder()
                .code(code)
                .barBegin(LocalDateTime.of(day, t))
                .open(px).high(px).low(px).close(px)
                .volume(new BigDecimal("1000000"))
                .periodMinutes(1)
                .build());
    }
}
