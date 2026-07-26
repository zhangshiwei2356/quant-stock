package com.quant.stock.trade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StampTaxAsOfTest {

    @Test
    void beforeCutUsesOnePerThousand() {
        assertEquals(0, StampTaxAsOf.RATE_BEFORE.compareTo(
                StampTaxAsOf.rateOn(LocalDate.of(2023, 8, 27), null)));
    }

    @Test
    void onAndAfterCutUsesHalfPerThousand() {
        assertEquals(0, StampTaxAsOf.RATE_AFTER.compareTo(
                StampTaxAsOf.rateOn(LocalDate.of(2023, 8, 28), null)));
        assertEquals(0, StampTaxAsOf.RATE_AFTER.compareTo(
                StampTaxAsOf.rateOn(LocalDate.of(2026, 1, 1), null)));
    }
}
