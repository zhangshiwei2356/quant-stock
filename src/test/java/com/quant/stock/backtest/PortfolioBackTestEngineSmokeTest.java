package com.quant.stock.backtest;

import com.quant.stock.backtest.dto.BackTestQueryDTO;
import com.quant.stock.backtest.dto.PortfolioResultDTO;
import com.quant.stock.calendar.TradingCalendar;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.BarPeriod;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.risk.OpenFilterService;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 组合回测冒烟：两只合成日 K，结果含指纹与 atrRisk。
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

        PortfolioBackTestEngine engine = new PortfolioBackTestEngine(
                props,
                mds,
                new PositionAmountUtil(props),
                new TradeCostModel(props),
                new OpenFilterService(props),
                new StrategyRegistry(Arrays.asList(new MaCrossStrategy(props), new HoldNothingStrategy()), props),
                new TradingCalendar());

        BackTestQueryDTO q = BackTestQueryDTO.builder()
                .stockCodeList(Arrays.asList("600036", "600519"))
                .initCapital(new BigDecimal("100000"))
                .build();
        PortfolioResultDTO result = engine.run(q);
        assertNotNull(result);
        assertNotNull(result.getFinalAsset());
        assertTrue(result.getFinalAsset().compareTo(BigDecimal.ZERO) > 0);
        assertNotNull(result.getConfigFingerprint());
        assertTrue(result.getConfigFingerprint().startsWith("v1:"));
        assertNotNull(result.getAtrRisk());
        assertTrue(result.getAtrRisk().containsKey("atrStopMultiplier"));
        assertNotNull(result.getCorrelation());
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
}
