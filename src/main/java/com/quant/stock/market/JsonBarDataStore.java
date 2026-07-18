package com.quant.stock.market;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.quant.stock.market.dto.BarDTO;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 classpath:data/kline 懒加载模拟K线 JSON（对应分表各周期文件）
 */
@Slf4j
@Component
public class JsonBarDataStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String BASE = "classpath:data/kline/";

    private final Map<String, List<BarDTO>> cache = new ConcurrentHashMap<String, List<BarDTO>>();

    @Getter
    private JSONObject meta;

    @Getter
    private final List<Map<String, String>> stocks = new ArrayList<Map<String, String>>();

    @PostConstruct
    public void init() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource metaRes = resolver.getResource(BASE + "meta.json");
            if (!metaRes.exists()) {
                log.warn("未找到模拟K线 meta.json，JsonBarDataStore 空载");
                return;
            }
            try (InputStream in = metaRes.getInputStream()) {
                byte[] bytes = readAll(in);
                meta = JSON.parseObject(new String(bytes, StandardCharsets.UTF_8));
            }
            JSONArray arr = meta.getJSONArray("stocks");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Map<String, String> m = new LinkedHashMap<String, String>();
                    m.put("code", o.getString("code"));
                    m.put("name", o.getString("name"));
                    m.put("basePrice", o.getString("basePrice"));
                    stocks.add(m);
                }
            }
            log.info("JsonBarDataStore 就绪: stocks={}, range={} ~ {}",
                    stocks.size(), meta.getString("start"), meta.getString("end"));
        } catch (Exception e) {
            log.error("加载 meta.json 失败: {}", e.getMessage());
        }
    }

    public boolean available() {
        return meta != null && !stocks.isEmpty();
    }

    public List<String> stockCodes() {
        List<String> codes = new ArrayList<String>();
        for (Map<String, String> s : stocks) {
            codes.add(s.get("code"));
        }
        return codes;
    }

    public List<BarDTO> getBars(String code, BarPeriod period) {
        if (code == null || period == null) {
            return Collections.emptyList();
        }
        String key = code + ":" + period.name();
        List<BarDTO> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            cached = loadFile(code, period);
            cache.put(key, cached);
            return cached;
        }
    }

    public List<BarDTO> getBars(String code, BarPeriod period, LocalDateTime start, LocalDateTime end) {
        List<BarDTO> all = getBars(code, period);
        if (all.isEmpty() || (start == null && end == null)) {
            return all;
        }
        List<BarDTO> filtered = new ArrayList<BarDTO>();
        for (BarDTO bar : all) {
            if (start != null && bar.getBarBegin().isBefore(start)) {
                continue;
            }
            if (end != null && bar.getBarBegin().isAfter(end)) {
                continue;
            }
            filtered.add(bar);
        }
        return filtered;
    }

    public Map<String, Object> summary() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("available", available());
        map.put("stocks", stocks);
        if (meta != null) {
            map.put("start", meta.getString("start"));
            map.put("end", meta.getString("end"));
            map.put("periods", meta.get("periods"));
            map.put("description", meta.getString("description"));
        }
        return map;
    }

    private List<BarDTO> loadFile(String code, BarPeriod period) {
        String path = BASE + code + "/" + period.name() + ".json";
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource res = resolver.getResource(path);
            if (!res.exists()) {
                log.debug("JSON不存在: {}", path);
                return Collections.emptyList();
            }
            long t0 = System.currentTimeMillis();
            JSONObject obj;
            try (InputStream in = res.getInputStream()) {
                obj = JSON.parseObject(readAll(in));
            }
            JSONArray bars = obj.getJSONArray("bars");
            List<BarDTO> list = new ArrayList<BarDTO>(bars == null ? 0 : bars.size());
            if (bars != null) {
                for (int i = 0; i < bars.size(); i++) {
                    JSONArray row = bars.getJSONArray(i);
                    list.add(BarDTO.builder()
                            .code(code)
                            .barBegin(LocalDateTime.parse(row.getString(0), FMT))
                            .open(toBd(row.get(1)))
                            .high(toBd(row.get(2)))
                            .low(toBd(row.get(3)))
                            .close(toBd(row.get(4)))
                            .volume(BigDecimal.valueOf(row.getLongValue(5)))
                            .build());
                }
            }
            log.info("加载JSON {} {} bars={} {}ms", code, period, list.size(), System.currentTimeMillis() - t0);
            return list;
        } catch (Exception e) {
            log.warn("加载JSON失败 {}: {}", path, e.getMessage());
            return Collections.emptyList();
        }
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(v.toString());
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
