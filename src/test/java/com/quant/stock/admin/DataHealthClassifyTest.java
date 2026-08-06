package com.quant.stock.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataHealthClassifyTest {

    @Test
    void classifyEmptyDaily_bjByCodePrefix() {
        assertEquals("bj", DataHealthService.classifyEmptyDaily("830001", "某某股份"));
        assertEquals("bj", DataHealthService.classifyEmptyDaily("430001", "北交所股"));
    }

    @Test
    void classifyEmptyDaily_likelyDelistedByName() {
        assertEquals("likely_delisted", DataHealthService.classifyEmptyDaily("600001", "退市某某"));
        assertEquals("likely_delisted", DataHealthService.classifyEmptyDaily("600002", "PT某某"));
        assertEquals("likely_delisted", DataHealthService.classifyEmptyDaily("600003", "某某摘牌"));
    }

    @Test
    void classifyEmptyDaily_missingOtherwise() {
        assertEquals("missing", DataHealthService.classifyEmptyDaily("600036", "招商银行"));
        assertEquals("missing", DataHealthService.classifyEmptyDaily("000001", "平安银行"));
    }

    @Test
    void isSuspendedName_detectsKeyword() {
        assertTrue(DataHealthService.isSuspendedName("某某停牌"));
        assertTrue(DataHealthService.isSuspendedName("停牌"));
        assertFalse(DataHealthService.isSuspendedName("招商银行"));
        assertFalse(DataHealthService.isSuspendedName(null));
        assertFalse(DataHealthService.isSuspendedName(""));
    }
}
