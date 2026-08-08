package com.quant.stock.pdf;


import lombok.extern.slf4j.Slf4j;
import com.itextpdf.text.Document;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.pdf.PdfWriter;
import com.itextpdf.tool.xml.XMLWorkerHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * HTML → PDF（对齐 zulin {@code PdfFileUtils.saveChinesePdf}）。
 */
@Slf4j
public final class HtmlPdfExporter {

    private HtmlPdfExporter() {
    }

    /** 将 XHTML 字符串转为 A4 PDF 字节数组。 */
    public static byte[] toPdfBytes(String xhtml) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChinesePdf(out, xhtml);
        return out.toByteArray();
    }

    /** 将 XHTML 写入输出流（UTF-8，宋体）。 */
    public static void writeChinesePdf(OutputStream out, String xhtml) {
        Document document = new Document(PageSize.A4, 48, 48, 52, 52);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            document.open();
            byte[] bytes = xhtml.getBytes(StandardCharsets.UTF_8);
            XMLWorkerHelper.getInstance().parseXHtml(
                    writer,
                    document,
                    new ByteArrayInputStream(bytes),
                    Charset.forName("UTF-8"),
                    new AsianFontProvider());
        } catch (Exception e) {
            log.error("HTML 转 PDF 异常", e);
            throw new IllegalStateException("HTML 转 PDF 失败: " + e.getMessage(), e);
        } finally {
            try {
                document.close();
            } catch (Exception ignored) {
                log.error("HTML 转 PDF 异常", ignored);
                // ignore
            }
        }
    }
}
