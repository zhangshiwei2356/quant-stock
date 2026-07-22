package com.quant.stock.trade;

import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeCostModelTest {

    @Test
    void commissionHasFiveYuanFloor() {
        QuantProperties props = new QuantProperties();
        props.setFeeRate(new BigDecimal("0.0003"));
        props.setStampTaxRate(new BigDecimal("0.001"));
        TradeCostModel model = new TradeCostModel(props);
        // 金额很小：佣金按 5 元保底
        assertEquals(0, new BigDecimal("5.00").compareTo(model.buyFee(new BigDecimal("1000"))));
        // 卖出：5 佣金 + 印花税 1
        assertEquals(0, new BigDecimal("6.00").compareTo(model.sellFee(new BigDecimal("1000"))));
    }
}
