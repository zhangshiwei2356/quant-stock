package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenFilterMarketCapHeuristicTest {

    @Test
    void starBoardUses40YiShares() {
        QuantProperties props = new QuantProperties();
        OpenFilterService filter = new OpenFilterService(props);
        // 688 优先于 6*：股价 10 → 市值 400 亿
        assertEquals(0, new BigDecimal("400.00")
                .compareTo(filter.estimateMarketCapYi("688001", new BigDecimal("10"))));
        // 普通沪市 6*
        assertEquals(0, new BigDecimal("1200.00")
                .compareTo(filter.estimateMarketCapYi("600036", new BigDecimal("10"))));
    }
}
