package com.quant.stock.pdf;

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
public final class HtmlPdfExporter {

    private HtmlPdfExporter() {
    }

    public static byte[] toPdfBytes(String xhtml) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChinesePdf(out, xhtml);
        return out.toByteArray();
    }

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
            throw new IllegalStateException("HTML 转 PDF 失败: " + e.getMessage(), e);
        } finally {
            try {
                document.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}
