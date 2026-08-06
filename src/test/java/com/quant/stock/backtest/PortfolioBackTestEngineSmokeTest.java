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
import com.quant.stock.session.OvernightGapStrategy;
import com.quant.stock.session.SessionContext;
import com.quant.stock.session.SessionDepProbe;
import com.quant.stock.session.SessionEvent;
import com.quant.stock.session.SessionOrderIntent;
import com.quant.stock.session.SessionPortfolioBackTestEngine;
import com.quant.stock.session.SessionStrategy;
import com.quant.stock.session.SessionBranch;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 组合回测冒烟：经典共享资金池 + session 共享资金池旁路。
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
    void sessionSharedCashAggregatesTwoLegs() {
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
                .strategyId(OvernightGapStrategy.ID)
                .engine("session")
                .build();
        PortfolioResultDTO result = engine.run(q);
        assertEquals("session", result.getEngine());
        assertNotNull(result.getStockResults());
        assertEquals(2, result.getStockResults().size());
        assertNotNull(result.getSessionBranchStats());
        assertEquals("SHARED_CASH_SESSION", result.getSessionBranchStats().get("mode"));
        assertTrue(result.getConfigFingerprint().contains("pfSessShared"));
        assertNotNull(result.getAnalysisSummary());
        assertTrue(result.getAnalysisSummary().contains("sharedCash"));
        assertNotNull(result.getCorrelation());
        assertTrue(result.getCorrelation().containsKey("avgCorrelation")
                || result.getCorrelation().containsKey("hint"));
    }

    @Test
    void sessionSharedCashSecondLegRejectedWhenCashTight() {
        QuantProperties props = new QuantProperties();
        props.setLimitPriceProtectEnabled(false);
        props.setMaxParticipationAdv(BigDecimal.ZERO);
        props.setMaxSinglePosition(BigDecimal.ONE);
        props.setMaxTotalPosition(new BigDecimal("10")); // 放宽总仓，逼出共享现金不足
        QuantProperties.Session sess = new QuantProperties.Session();
        sess.setFillMode("BAR_CLOSE");
        sess.setMatchingEnabled(true);
        props.setSession(sess);

        MarketDataService mds = mock(MarketDataService.class);
        when(mds.getKline(eq("600036"), eq(BarPeriod.MIN_1), any(), any()))
                .thenReturn(syntheticSessionMinutes("600036", 3));
        when(mds.getKline(eq("600519"), eq(BarPeriod.MIN_1), any(), any()))
                .thenReturn(syntheticSessionMinutes("600519", 3));

        SessionPortfolioBackTestEngine spe = new SessionPortfolioBackTestEngine(
                mds, new SessionDepProbe(mds), props, new TradeCostModel(props), new PositionAmountUtil(props));

        // 两股同分钟各买 100 股@10 ≈ 各需 ~1000+费；总资金 1500 仅够一腿
        SessionStrategy buyer = new SessionStrategy() {
            private final Set<String> fired = new HashSet<String>();

            @Override
            public String sessionId() {
                return "cashRace";
            }

            @Override
            public void onSessionOpen(SessionContext ctx, List<SessionEvent> out) {
            }

            @Override
            public void onBranchBar(SessionContext ctx, List<SessionEvent> out) {
            }

            @Override
            public void onSessionClose(SessionContext ctx, List<SessionEvent> out) {
            }

            @Override
            public List<SessionOrderIntent> pollIntents(SessionContext ctx) {
                if (ctx.getBranch() == SessionBranch.OPEN && ctx.getPositionShares() == 0
                        && fired.add(ctx.getStockCode())) {
                    return Collections.singletonList(SessionOrderIntent.buy(100, "race-buy"));
                }
                return Collections.emptyList();
            }
        };

        PortfolioResultDTO result = spe.run(BackTestQueryDTO.builder()
                .stockCodeList(Arrays.asList("600036", "600519"))
                .initCapital(new BigDecimal("1500"))
                .build(), buyer, false);

        assertEquals("SHARED_CASH_SESSION", result.getSessionBranchStats().get("mode"));
        assertEquals(1, result.getTotalTradeNum().intValue(), "共享池仅应成交一腿买单");
        assertEquals("600036", result.getTrades().get(0).getStockCode());
        assertTrue(result.getAnalysisEvents().stream().anyMatch(e ->
                "REJECT_BUY".equals(e.getType()) && e.getReason() != null && e.getReason().contains("现金不足")));
        assertNotNull(result.getSessionEvents());
        assertTrue(result.getSessionEvents().stream().anyMatch(e -> "REJECT_BUY".equals(e.getType())));
    }

    @Test
    void sessionSharedCashHaltSellsAndBlocksNewOpen() {
        QuantProperties props = new QuantProperties();
        props.setLimitPriceProtectEnabled(false);
        props.setMaxParticipationAdv(BigDecimal.ZERO);
        props.setMaxSinglePosition(BigDecimal.ONE);
        props.setMaxTotalPosition(new BigDecimal("10"));
        props.setDrawdownHaltPct(new BigDecimal("0.20"));
        props.setDrawdownDurationHaltDays(0);
        QuantProperties.Session sess = new QuantProperties.Session();
        sess.setFillMode("BAR_CLOSE");
        sess.setMatchingEnabled(true);
        props.setSession(sess);

        MarketDataService mds = mock(MarketDataService.class);
        // D0 价 10 买入；D1 起暴跌至 5 → 回撤触发熔断强平；之后再尝试开仓应拒
        when(mds.getKline(eq("600036"), eq(BarPeriod.MIN_1), any(), any()))
                .thenReturn(syntheticCrashMinutes("600036", new BigDecimal("10"), new BigDecimal("5"), 4));

        SessionPortfolioBackTestEngine spe = new SessionPortfolioBackTestEngine(
                mds, new SessionDepProbe(mds), props, new TradeCostModel(props), new PositionAmountUtil(props));

        SessionStrategy trader = new SessionStrategy() {
            private boolean boughtOnce;

            @Override
            public String sessionId() {
                return "haltDemo";
            }

            @Override
            public void onSessionOpen(SessionContext ctx, List<SessionEvent> out) {
            }

            @Override
            public void onBranchBar(SessionContext ctx, List<SessionEvent> out) {
            }

            @Override
            public void onSessionClose(SessionContext ctx, List<SessionEvent> out) {
            }

            @Override
            public List<SessionOrderIntent> pollIntents(SessionContext ctx) {
                if (ctx.getBranch() == SessionBranch.OPEN && ctx.getPositionShares() == 0) {
                    if (!boughtOnce) {
                        boughtOnce = true;
                        // volume≤0：按仓位工具满仓，便于暴跌触发深度熔断
                        return Collections.singletonList(SessionOrderIntent.buy(0, "pre-halt-buy"));
                    }
                    return Collections.singletonList(SessionOrderIntent.buy(100, "post-halt-buy"));
                }
                return Collections.emptyList();
            }
        };

        PortfolioResultDTO result = spe.run(BackTestQueryDTO.builder()
                .stockCodeList(Collections.singletonList("600036"))
                .initCapital(new BigDecimal("100000"))
                .build(), trader, false);

        assertEquals(Boolean.TRUE, result.getSessionBranchStats().get("halted"));
        assertTrue(result.getTrades().stream().anyMatch(t -> "BUY".equals(t.getSide())));
        assertTrue(result.getTrades().stream().anyMatch(t -> "SELL".equals(t.getSide())),
                "熔断后应卖出持仓");
        assertTrue(result.getSessionEvents().stream().anyMatch(e ->
                "REJECT_BUY".equals(e.getType()) && e.getDetail() != null && e.getDetail().contains("账户禁开")));
    }

    @Test
    void resolveEngineDefaultsOvernightGapToSession() {
        QuantProperties props = new QuantProperties();
        assertEquals("session", PortfolioBackTestEngine.resolveEngine(
                BackTestQueryDTO.builder().build(), new OvernightGapStrategy(props)));
        assertEquals("classic", PortfolioBackTestEngine.resolveEngine(
                BackTestQueryDTO.builder().build(), new MaCrossStrategy(props)));
        assertEquals("classic", PortfolioBackTestEngine.resolveEngine(
                BackTestQueryDTO.builder().engine("classic").build(), new OvernightGapStrategy(props)));
    }

    private static PortfolioBackTestEngine engine(QuantProperties props, MarketDataService mds) {
        EffectiveParamsService eps = mock(EffectiveParamsService.class);
        when(eps.resolve(anyString())).thenAnswer(inv -> QuantPropertiesCopy.copy(props));
        when(eps.resolve(anyString(), any())).thenAnswer(inv -> QuantPropertiesCopy.copy(props));
        when(eps.hasSparse(anyString())).thenReturn(false);
        SessionPortfolioBackTestEngine sessionPortfolio = new SessionPortfolioBackTestEngine(
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
                        new OvernightGapStrategy(props)), props),
                new TradingCalendar(),
                sessionPortfolio);
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

    /** 首日 highPx，次日起 crashPx，用于熔断验收。 */
    private static List<BarDTO> syntheticCrashMinutes(String code, BigDecimal highPx, BigDecimal crashPx, int days) {
        List<BarDTO> out = new ArrayList<BarDTO>();
        LocalDate d = LocalDate.of(2026, 3, 2);
        for (int day = 0; day < days; day++) {
            LocalDate cur = d.plusDays(day);
            BigDecimal px = day == 0 ? highPx : crashPx;
            addMin(out, code, cur, LocalTime.of(9, 30), px);
            addMin(out, code, cur, LocalTime.of(9, 45), px);
            addMin(out, code, cur, LocalTime.of(10, 30), px);
            addMin(out, code, cur, LocalTime.of(14, 0), px);
            addMin(out, code, cur, LocalTime.of(14, 45), px);
            addMin(out, code, cur, LocalTime.of(14, 59), px);
        }
        while (out.size() < 30) {
            addMin(out, code, d.plusDays(days), LocalTime.of(10, out.size() % 30), crashPx);
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
