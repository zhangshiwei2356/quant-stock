package com.quant.stock.pool;

import com.quant.stock.pdf.HtmlPdfExporter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目标池扫描报告：XHTML → PDF 冒烟。
 */
class TradePoolReportPdfTest {

    @Test
    void buildPoolReportXhtmlToPdf() {
        Map<String, Object> rebuild = new LinkedHashMap<String, Object>();
        rebuild.put("universe", 5000);
        rebuild.put("afterCoarse", 800);
        rebuild.put("afterScan", 120);
        rebuild.put("afterLiquidity", 90);
        rebuild.put("selected", 1);
        rebuild.put("scoreMin", 60);
        rebuild.put("batchId", "20260806120000-abcd1234");

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("code", "600000");
        row.put("name", "浦发银行");
        row.put("scorePct", "72.5分");
        row.put("reason", "金叉可买");
        row.put("source", "BATCH_SCAN");
        row.put("reportId", 1L);
        rows.add(row);

        String xhtml = TradePoolService.buildPoolReportXhtml(
                rows, Collections.singletonList("600000"), rebuild);
        byte[] pdf = HtmlPdfExporter.toPdfBytes(xhtml);
        assertTrue(pdf.length > 200, "pdf too small");
        assertTrue(pdf[0] == 0x25 && pdf[1] == 0x50 && pdf[2] == 0x44 && pdf[3] == 0x46);
    }
}
