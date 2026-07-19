package com.quant.stock.pdf;

import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Font;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.tool.xml.XMLWorkerFontProvider;

/**
 * 中文字体（与 zsw-utils / zulin 相同：itext-asian STSong-Light）。
 */
public class AsianFontProvider extends XMLWorkerFontProvider {

    @Override
    public Font getFont(final String fontname, final String encoding, final boolean embedded,
                        final float size, final int style, final BaseColor color) {
        try {
            BaseFont bf = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            Font font = new Font(bf, size <= 0 ? 12 : size, style, color);
            if (color != null) {
                font.setColor(color);
            }
            return font;
        } catch (Exception e) {
            return super.getFont(fontname, encoding, embedded, size, style, color);
        }
    }
}
