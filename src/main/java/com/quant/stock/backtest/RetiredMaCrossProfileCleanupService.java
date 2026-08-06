package com.quant.stock.backtest;

import com.quant.stock.mapper.BacktestAnalysisMapper;
import com.quant.stock.mapper.BacktestRecordMapper;
import com.quant.stock.mapper.StrategyParamMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 下线不成功的金叉对照画像后，清理其回测历史、分析事件与策略参数包。
 * <p>
 * 目标 id：{@code maCrossTrend} / {@code maCrossVolume} / {@code maCrossStrict}（大小写不敏感）。
 * 启动幂等执行一次；也可运维手动再跑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class RetiredMaCrossProfileCleanupService {

    /** 已下线对照画像的规范 id（小写键）。 */
    public static final Set<String> RETIRED_IDS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    "macrosstrend", "macrossvolume", "macrossstrict")));

    private final BacktestRecordMapper backtestRecordMapper;
    private final BacktestAnalysisMapper backtestAnalysisMapper;
    private final ObjectProvider<StrategyParamMapper> strategyParamMapperProvider;

    /** 启动后清理（在 strategy_id 补全之后；{@link Order} 略大）。 */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void onReady() {
        try {
            Map<String, Object> r = cleanup();
            log.info("下线金叉对照画像清理: {}", r);
        } catch (Exception e) {
            log.warn("下线金叉对照画像清理失败: {}", e.getMessage());
        }
    }

    /**
     * 删除已下线策略的回测记录、对应分析、稀疏参数包。
     *
     * @return matchedStrategyIds / analysisDeleted / recordsDeleted / paramsDeleted
     */
    public Map<String, Object> cleanup() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<String> matched = resolveMatchedIds();
        out.put("matchedStrategyIds", matched);
        if (matched.isEmpty()) {
            out.put("analysisDeleted", 0);
            out.put("recordsDeleted", 0);
            out.put("paramsDeleted", 0);
            out.put("ok", true);
            return out;
        }

        List<String> recordIds = backtestRecordMapper.selectRecordIdsByStrategyIds(matched);
        if (recordIds == null) {
            recordIds = Collections.emptyList();
        }
        int analysisDeleted = 0;
        if (!recordIds.isEmpty()) {
            // 分批避免 IN 列表过大
            final int batch = 200;
            for (int i = 0; i < recordIds.size(); i += batch) {
                int end = Math.min(i + batch, recordIds.size());
                analysisDeleted += backtestAnalysisMapper.deleteByRecordIds(recordIds.subList(i, end));
            }
        }
        int recordsDeleted = backtestRecordMapper.deleteByStrategyIds(matched);

        int paramsDeleted = 0;
        StrategyParamMapper paramMapper = strategyParamMapperProvider.getIfAvailable();
        if (paramMapper != null) {
            Set<String> paramIds = new LinkedHashSet<String>(matched);
            for (String id : paramIds) {
                paramsDeleted += paramMapper.deleteByStrategyId(id);
            }
        }

        out.put("analysisDeleted", analysisDeleted);
        out.put("recordsDeleted", recordsDeleted);
        out.put("paramsDeleted", paramsDeleted);
        out.put("ok", true);
        return out;
    }

    /** 库内 distinct strategy_id 中匹配已下线集合者（保留原文以便 DELETE）。 */
    List<String> resolveMatchedIds() {
        List<String> distinct = backtestRecordMapper.selectDistinctStrategyIds();
        if (distinct == null || distinct.isEmpty()) {
            // 仍按规范 id 尝试删除（表空或尚无 distinct）
            return Arrays.asList("maCrossTrend", "maCrossVolume", "maCrossStrict");
        }
        Set<String> out = new LinkedHashSet<String>();
        for (String raw : distinct) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String key = raw.trim().toLowerCase(Locale.ROOT);
            if (RETIRED_IDS.contains(key)) {
                out.add(raw.trim());
            }
        }
        // 规范 id 始终带上，覆盖尚无历史行但有 param 的情况由 params 循环处理；记录删除也无害
        out.add("maCrossTrend");
        out.add("maCrossVolume");
        out.add("maCrossStrict");
        return new ArrayList<String>(out);
    }
}
