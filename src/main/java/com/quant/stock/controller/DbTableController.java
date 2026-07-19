package com.quant.stock.controller;

import com.quant.stock.admin.DbTableBrowseService;
import com.quant.stock.admin.DbTableCatalog;
import com.quant.stock.admin.DbTableCatalog.TableDef;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用 MySQL 表白名单浏览（只读分页）。
 */
@RestController
@RequestMapping("/api/db")
@RequiredArgsConstructor
public class DbTableController {

    private final ObjectProvider<DbTableBrowseService> browseServiceProvider;

    @GetMapping("/tables")
    public Map<String, Object> tables() {
        DbTableBrowseService svc = browseServiceProvider.getIfAvailable();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (svc == null) {
            m.put("enabled", false);
            m.put("hint", "需要 quant.db-enabled=true");
            m.put("tables", catalogFallback());
            return m;
        }
        m.put("enabled", true);
        m.put("tables", svc.listTables());
        return m;
    }

    @GetMapping("/tables/{name}")
    public Map<String, Object> page(
            @PathVariable("name") String name,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        DbTableBrowseService svc = browseServiceProvider.getIfAvailable();
        if (svc == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "需要 quant.db-enabled=true");
        }
        try {
            return svc.page(name, page, size);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static List<Map<String, Object>> catalogFallback() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (TableDef def : DbTableCatalog.all()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", def.getName());
            m.put("title", def.getTitle());
            m.put("module", def.getModule());
            m.put("orderBy", def.getOrderBy());
            m.put("purpose", def.getPurpose());
            m.put("source", def.getSource());
            m.put("usage", def.getUsage());
            m.put("rowCount", null);
            m.put("exists", false);
            list.add(m);
        }
        return list;
    }
}
