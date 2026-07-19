package com.quant.stock.pdf;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 static/docs 下知识/应用说明合并为服务端 PDF。
 */
@Service
public class DocsPdfService {

    private static final class Topic {
        final String id;
        final String group;
        final String title;
        final String file;

        Topic(String id, String group, String title, String file) {
            this.id = id;
            this.group = group;
            this.title = title;
            this.file = file;
        }
    }

    private static final List<Topic> TOPICS;

    static {
        List<Topic> list = new ArrayList<Topic>();
        list.add(new Topic("app", "app", "系统概述", "app.html"));
        list.add(new Topic("rules", "app", "交易规则", "rules.html"));
        list.add(new Topic("memo", "app", "应用待办", "memo.html"));
        list.add(new Topic("ashare", "stock", "A股基础", "ashare.html"));
        list.add(new Topic("session", "stock", "交易时间", "session.html"));
        list.add(new Topic("kline", "stock", "K线", "kline.html"));
        list.add(new Topic("ma", "stock", "均线 MA与金叉", "ma.html"));
        list.add(new Topic("volume", "stock", "成交量与放量", "volume.html"));
        list.add(new Topic("rsi", "stock", "RSI相对强弱", "rsi.html"));
        list.add(new Topic("atr", "stock", "ATR真实波幅", "atr.html"));
        list.add(new Topic("adx", "stock", "ADX趋势强度", "adx.html"));
        list.add(new Topic("boll", "stock", "BOLL 布林带", "boll.html"));
        list.add(new Topic("limit", "stock", "涨跌停与停牌", "limit.html"));
        list.add(new Topic("tplus1", "stock", "T+1与整手", "tplus1.html"));
        list.add(new Topic("cost", "stock", "交易成本", "cost.html"));
        list.add(new Topic("position", "stock", "仓位与金字塔", "position.html"));
        list.add(new Topic("risk", "stock", "账户风控", "risk.html"));
        list.add(new Topic("fill", "stock", "撮合时机", "fill.html"));
        list.add(new Topic("metrics", "stock", "权益回撤与胜率", "metrics.html"));
        list.add(new Topic("backtest", "stock", "回测要点", "backtest.html"));
        TOPICS = Collections.unmodifiableList(list);
    }

    public Map<String, Object> export(String group) {
        if (!"stock".equals(group) && !"app".equals(group)) {
            throw new IllegalArgumentException("group 仅支持 stock 或 app");
        }
        String packTitle = "app".equals(group) ? "应用说明" : "量化知识";
        List<Topic> selected = new ArrayList<Topic>();
        for (Topic t : TOPICS) {
            if (group.equals(t.group)) {
                selected.add(t);
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalStateException("没有可导出的文档");
        }

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        StringBuilder body = new StringBuilder();
        body.append("<h1>Quant Stock · ").append(esc(packTitle)).append("</h1>");
        body.append("<p>共 ").append(selected.size()).append(" 篇 · 导出时间 ").append(esc(stamp)).append("</p>");
        body.append("<hr/>");

        int i = 1;
        for (Topic t : selected) {
            String raw = readDoc(t.file);
            String clean = sanitizeFragment(raw);
            body.append("<h2>").append(i++).append(". ").append(esc(t.title)).append("</h2>");
            body.append(clean);
            body.append("<br/><br/>");
        }

        String xhtml = wrapXhtml(body.toString());
        byte[] pdf = HtmlPdfExporter.toPdfBytes(xhtml);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("filename", "QuantStock-" + packTitle + ".pdf");
        out.put("bytes", pdf);
        out.put("count", selected.size());
        return out;
    }

    private static String readDoc(String fileName) {
        try {
            ClassPathResource res = new ClassPathResource("static/docs/" + fileName);
            if (!res.exists()) {
                return "<p>（文档缺失：" + esc(fileName) + "）</p>";
            }
            return StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<p>（文档读取失败：" + esc(fileName) + "）</p>";
        }
    }

    private static String sanitizeFragment(String html) {
        String s = html == null ? "" : html;
        s = s.replaceAll("(?s)<!--.*?-->", "");
        Document doc = Jsoup.parseBodyFragment(s);
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false);
        doc.select("script, style, button, noscript").remove();
        for (Element el : doc.select("[onclick], [onload], [style]")) {
            el.removeAttr("onclick");
            el.removeAttr("onload");
            // XMLWorker 对复杂 style 支持差，去掉内联样式更稳
            el.removeAttr("style");
        }
        String body = doc.body().html();
        // 再过一遍白名单，去掉未知标签
        return Jsoup.clean(body, Safelist.relaxed()
                .addTags("h1", "h2", "h3", "h4", "h5", "hr", "code", "pre", "b", "i", "strong", "em", "br")
                .addAttributes("a", "href")
                .addAttributes("td", "colspan", "rowspan")
                .addAttributes("th", "colspan", "rowspan"));
    }

    private static String wrapXhtml(String bodyHtml) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" "
                + "\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"/>"
                + "<title>Quant Stock Docs</title>"
                + "<style type=\"text/css\">"
                + "body{font-size:11pt;line-height:1.55;color:#222;}"
                + "h1{font-size:18pt;margin:0 0 10px 0;}"
                + "h2{font-size:14pt;margin:18px 0 8px 0;}"
                + "h3,h4{font-size:12pt;margin:12px 0 6px 0;}"
                + "p,li{font-size:11pt;}"
                + "ul,ol{margin:6px 0 6px 22px;}"
                + "code,pre{font-size:10pt;}"
                + "hr{border:none;border-top:1px solid #999;margin:12px 0;}"
                + "table{border-collapse:collapse;width:100%;margin:8px 0;}"
                + "th,td{border:1px solid #aaa;padding:4px 6px;font-size:10pt;}"
                + "</style></head><body>"
                + bodyHtml
                + "</body></html>";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
