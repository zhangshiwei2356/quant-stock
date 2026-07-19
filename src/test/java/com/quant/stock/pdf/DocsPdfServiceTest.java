package com.quant.stock.pdf;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocsPdfServiceTest {

    @Test
    void exportStockPdfHasBytes() {
        DocsPdfService svc = new DocsPdfService();
        Map<String, Object> pack = svc.export("stock");
        byte[] bytes = (byte[]) pack.get("bytes");
        assertNotNull(bytes);
        assertTrue(bytes.length > 500, "pdf too small");
        // %PDF
        assertTrue(bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46);
    }

    @Test
    void exportAppPdfHasBytes() {
        DocsPdfService svc = new DocsPdfService();
        Map<String, Object> pack = svc.export("app");
        byte[] bytes = (byte[]) pack.get("bytes");
        assertNotNull(bytes);
        assertTrue(bytes.length > 200);
    }
}
