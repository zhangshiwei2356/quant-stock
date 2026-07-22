package com.quant.stock.backtest;

import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.risk.OpenFilterService;
import com.quant.stock.strategy.MaCrossStrategy;
import com.quant.stock.trade.TradeCostModel;
import com.quant.stock.util.PositionAmountUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回测引擎冒烟：合成日 K 足以跑通，结果非空。
 */
class BackTestEngineSmokeTest {

    @Test
    void runOnSyntheticDayBarsReturnsResult() {
        QuantProperties props = new QuantProperties();
        props.setQuietOpenEnabled(false);
        props.setQuietCloseEnabled(false);
        props.setMarketCapFilterEnabled(false);
        props.setMinAvgVolume20(1L);
        props.setStopLossEnabled(true);
        props.setFeeRate(new BigDecimal("0.0003"));
        OpenFilterService openFilter = new OpenFilterService(props);
        BackTestEngine engine = new BackTestEngine(
                props,
                new PositionAmountUtil(props),
                new MaCrossStrategy(props),
                new TradeCostModel(props),
                openFilter);

        List<BarDTO> bars = syntheticUptrendDays("600036", 120);
        BackTestResult result = engine.run("600036", bars, new BigDecimal("100000"));
        assertNotNull(result);
        assertNotNull(result.getFinalAsset());
        assertTrue(result.getFinalAsset().compareTo(BigDecimal.ZERO) > 0);
        assertNotNull(result.getTotalRate());
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
