package com.quant.stock.pdf;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * 读取项目根目录 {@code README.md} 并转为 HTML，供「应用说明 → 项目 README」展示。
 */
@Service
public class DocsReadmeService {

    private final Parser parser;
    private final HtmlRenderer renderer;

    public DocsReadmeService() {
        List<Extension> extensions = Arrays.<Extension>asList(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        this.renderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).build();
    }

    public String toHtmlFragment() throws IOException {
        Path path = locateReadme();
        if (path == null) {
            return "<div class=\"readme-md\"><p>未找到 <code>README.md</code>。"
                    + "请从项目根目录启动（<code>mvn spring-boot:run</code>）。</p></div>";
        }
        String md = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        Node doc = parser.parse(md);
        String body = renderer.render(doc);
        return "<div class=\"readme-md\" data-readme-path=\""
                + escapeAttr(path.toAbsolutePath().normalize().toString())
                + "\"><p class=\"readme-md-banner hint\">以下为仓库根目录 <code>README.md</code> 实时渲染"
                + "（架构图为 Mermaid，需联网加载渲染库；离线时显示源码）。</p>"
                + body + "</div>";
    }

    private Path locateReadme() {
        String userDir = System.getProperty("user.dir", ".");
        Path[] candidates = new Path[]{
                Paths.get("README.md"),
                Paths.get(userDir, "README.md"),
                Paths.get(userDir).resolve("..").resolve("README.md").normalize(),
                Paths.get(userDir).resolve("quant-stock").resolve("README.md").normalize()
        };
        for (Path p : candidates) {
            if (p != null && Files.isRegularFile(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static String escapeAttr(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
