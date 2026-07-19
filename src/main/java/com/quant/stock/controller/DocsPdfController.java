package com.quant.stock.controller;

import com.quant.stock.pdf.DocsPdfService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 文档 PDF 导出（服务端 iText，替代浏览器 html2pdf）。
 */
@RestController
@RequestMapping("/api/docs")
@RequiredArgsConstructor
public class DocsPdfController {

    private final DocsPdfService docsPdfService;

    @GetMapping("/pdf/{group}")
    public ResponseEntity<byte[]> download(@PathVariable("group") String group) {
        try {
            Map<String, Object> pack = docsPdfService.export(group);
            byte[] bytes = (byte[]) pack.get("bytes");
            String filename = String.valueOf(pack.get("filename"));
            String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8.name()).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                    .contentType(MediaType.APPLICATION_PDF)
                    .contentLength(bytes.length)
                    .body(bytes);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "PDF 生成失败: " + e.getMessage());
        }
    }
}
