package com.quant.stock.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.stock.admin.dto.StrategyParamDO;
import com.quant.stock.config.ConfigFingerprint;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.mapper.StrategyParamMapper;
import com.quant.stock.strategy.StrategyRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略参数包：三层叠层 resolve + 稀疏保存。
 */
@Slf4j
@Service
public class EffectiveParamsService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QuantProperties global;
    private final StrategyRegistry strategyRegistry;
    private final ObjectProvider<StrategyParamMapper> mapperProvider;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public EffectiveParamsService(QuantProperties global,
                                  StrategyRegistry strategyRegistry,
                                  ObjectProvider<StrategyParamMapper> mapperProvider,
                                  ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.global = global;
        this.strategyRegistry = strategyRegistry;
        this.mapperProvider = mapperProvider;
        this.jdbcProvider = jdbcProvider;
    }

    @PostConstruct
    public void ensureTable() {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null || !global.isDbEnabled()) {
            return;
        }
        try {
            jdbc.execute(
                    "CREATE TABLE IF NOT EXISTS `strategy_param` ("
                            + "`strategy_id` VARCHAR(64) NOT NULL,"
                            + "`params_json` TEXT,"
                            + "`version` INT NOT NULL DEFAULT 0,"
                            + "`updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                            + "`updated_by` VARCHAR(64) DEFAULT 'ops',"
                            + "PRIMARY KEY (`strategy_id`)"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (Exception e) {
            log.warn("确保 strategy_param 表失败: {}", e.getMessage());
        }
    }

    public boolean hasSparse(String strategyId) {
        return !getSparse(strategyId).isEmpty();
    }

    public Map<String, String> getSparse(String strategyId) {
        String id = normalizeId(strategyId);
        StrategyParamMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null || !global.isDbEnabled() || id.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            StrategyParamDO row = mapper.selectByStrategyId(id);
            if (row == null || row.getParamsJson() == null || row.getParamsJson().trim().isEmpty()) {
                return Collections.emptyMap();
            }
            return parseJson(row.getParamsJson());
        } catch (Exception e) {
            log.warn("读取 strategy_param 失败 id={}: {}", id, e.getMessage());
            return Collections.emptyMap();
        }
    }

    public Integer getVersion(String strategyId) {
        String id = normalizeId(strategyId);
        StrategyParamMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null || !global.isDbEnabled() || id.isEmpty()) {
            return null;
        }
        try {
            StrategyParamDO row = mapper.selectByStrategyId(id);
            return row == null ? null : row.getVersion();
        } catch (Exception e) {
            return null;
        }
    }

    /** 全局底 ⊕ 稀疏包 → 生效快照（不写回单例）。 */
    public QuantProperties resolve(String strategyId) {
        QuantProperties snap = QuantPropertiesCopy.copy(global);
        Map<String, String> sparse = getSparse(strategyId);
        for (Map.Entry<String, String> e : sparse.entrySet()) {
            if (!WritableParamKeys.isWritable(e.getKey())) {
                continue;
            }
            try {
                WritableParamApplier.apply(snap, e.getKey(), e.getValue());
            } catch (Exception ex) {
                log.warn("忽略非法策略包键 {}={}: {}", e.getKey(), e.getValue(), ex.getMessage());
            }
        }
        return snap;
    }

    public Map<String, Object> saveSparse(String strategyId, Map<String, Object> updates,
                                         List<String> clearKeys, boolean confirm, Integer expectedVersion) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (!confirm) {
            out.put("ok", false);
            out.put("message", "请确认修改：confirm 须为 true");
            return out;
        }
        String id = normalizeId(strategyId);
        if (id.isEmpty() || !strategyRegistry.contains(id)) {
            out.put("ok", false);
            out.put("message", "未知策略 id: " + strategyId);
            return out;
        }
        StrategyParamMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null || !global.isDbEnabled()) {
            out.put("ok", false);
            out.put("message", "需要 quant.db-enabled=true 才能保存策略参数包");
            return out;
        }
        Map<String, String> sparse = new LinkedHashMap<String, String>(getSparse(id));
        Integer curVer = getVersion(id);
        if (expectedVersion != null && curVer != null && !expectedVersion.equals(curVer)) {
            out.put("ok", false);
            out.put("message", "版本冲突：当前 version=" + curVer + "，请刷新后重试");
            out.put("version", curVer);
            return out;
        }
        List<String> errors = new ArrayList<String>();
        List<String> applied = new ArrayList<String>();
        if (clearKeys != null) {
            for (String k : clearKeys) {
                if (k == null || k.trim().isEmpty()) {
                    continue;
                }
                String key = k.trim();
                if (sparse.remove(key) != null) {
                    applied.add("clear:" + key);
                }
            }
        }
        if (updates != null) {
            for (Map.Entry<String, Object> e : updates.entrySet()) {
                String key = e.getKey() == null ? "" : e.getKey().trim();
                if (key.isEmpty()) {
                    continue;
                }
                if (!WritableParamKeys.isWritable(key)) {
                    errors.add("不在白名单: " + key);
                    continue;
                }
                String raw = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
                try {
                    // 校验类型
                    QuantProperties probe = QuantPropertiesCopy.copy(global);
                    WritableParamApplier.apply(probe, key, raw);
                    sparse.put(key, WritableParamApplier.formatStored(key, raw));
                    applied.add(key);
                } catch (Exception ex) {
                    errors.add(key + ": " + ex.getMessage());
                }
            }
        }
        if (!errors.isEmpty() && applied.isEmpty()) {
            out.put("ok", false);
            out.put("errors", errors);
            out.put("message", "全部失败");
            return out;
        }
        try {
            StrategyParamDO row = new StrategyParamDO();
            row.setStrategyId(id);
            row.setParamsJson(MAPPER.writeValueAsString(sparse));
            row.setUpdatedBy("ops");
            mapper.upsert(row);
            Integer newVer = getVersion(id);
            out.put("ok", errors.isEmpty());
            out.put("applied", applied);
            out.put("errors", errors);
            out.put("strategyId", id);
            out.put("version", newVer);
            out.put("sparse", sparse);
            out.put("configFingerprint", ConfigFingerprint.of(resolve(id),
                    strategyRegistry.resolve(id).fingerprintId(), null));
            out.put("message", errors.isEmpty()
                    ? ("策略包已更新 " + applied.size() + " 项")
                    : ("部分失败：成功 " + applied.size() + "，错误 " + errors.size()));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "保存失败: " + e.getMessage());
        }
        return out;
    }

    private static String normalizeId(String strategyId) {
        return strategyId == null ? "" : strategyId.trim();
    }

    private static Map<String, String> parseJson(String json) throws Exception {
        Map<String, Object> raw = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
        });
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            out.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return out;
    }
}
