package com.quant.stock.backtest;

import com.quant.stock.admin.ActiveStrategyService;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.mapper.BacktestAnalysisMapper;
import com.quant.stock.mapper.BacktestRecordMapper;
import com.quant.stock.mapper.StrategyParamMapper;
import com.quant.stock.strategy.StrategyRegistry;
import com.quant.stock.trade.LiveLedgerService;
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
 * 下线不成功的金叉对照画像后，清理其回测历史、分析事件、策略参数包，
 * 以及误写的激活策略配置。
 * <p>
 * 匹配：注册 id（{@code maCrossTrend} 等）与历史指纹类名（{@code MaCrossTrendStrategy} 等），
 * 大小写不敏感。启动幂等执行一次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class RetiredMaCrossProfileCleanupService {

    /** 已下线对照画像：注册 id + 指纹类名（小写键）。 */
    public static final Set<String> RETIRED_IDS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    "macrosstrend", "macrossvolume", "macrossstrict",
                    "macrosstrendstrategy", "macrossvolumestrategy", "macrossstrictstrategy")));

    private static final List<String> CANONICAL_DELETE_IDS = Collections.unmodifiableList(Arrays.asList(
            "maCrossTrend", "maCrossVolume", "maCrossStrict",
            "MaCrossTrendStrategy", "MaCrossVolumeStrategy", "MaCrossStrictStrategy"));

    private final BacktestRecordMapper backtestRecordMapper;
    private final BacktestAnalysisMapper backtestAnalysisMapper;
    private final ObjectProvider<StrategyParamMapper> strategyParamMapperProvider;
    private final ObjectProvider<LiveLedgerService> ledgerProvider;
    private final QuantProperties props;

    /** 启动后清理；Order 略高于默认，便于在激活策略加载后纠正死配置。 */
    @EventListener(ApplicationReadyEvent.class)
    @Order(200)
    public void onReady() {
        try {
            Map<String, Object> r = cleanup();
            log.info("下线金叉对照画像清理: {}", r);
        } catch (Exception e) {
            log.error("下线金叉对照画像清理失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 删除已下线策略的回测记录、对应分析、稀疏参数包；必要时重置激活策略。
     *
     * @return matchedStrategyIds / analysisDeleted / recordsDeleted / paramsDeleted / activeStrategyReset
     */
    public Map<String, Object> cleanup() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<String> matched = resolveMatchedIds();
        out.put("matchedStrategyIds", matched);

        int analysisDeleted = 0;
        int recordsDeleted = 0;
        int paramsDeleted = 0;
        if (!matched.isEmpty()) {
            List<String> recordIds = backtestRecordMapper.selectRecordIdsByStrategyIds(matched);
            if (recordIds == null) {
                recordIds = Collections.emptyList();
            }
            if (!recordIds.isEmpty()) {
                final int batch = 200;
                for (int i = 0; i < recordIds.size(); i += batch) {
                    int end = Math.min(i + batch, recordIds.size());
                    analysisDeleted += backtestAnalysisMapper.deleteByRecordIds(recordIds.subList(i, end));
                }
            }
            recordsDeleted = backtestRecordMapper.deleteByStrategyIds(matched);

            StrategyParamMapper paramMapper = strategyParamMapperProvider.getIfAvailable();
            if (paramMapper != null) {
                for (String id : new LinkedHashSet<String>(matched)) {
                    paramsDeleted += paramMapper.deleteByStrategyId(id);
                }
            }
        }

        boolean activeReset = resetRetiredActiveStrategy();

        out.put("analysisDeleted", analysisDeleted);
        out.put("recordsDeleted", recordsDeleted);
        out.put("paramsDeleted", paramsDeleted);
        out.put("activeStrategyReset", activeReset);
        out.put("ok", true);
        return out;
    }

    /** 库内 distinct strategy_id 中匹配已下线集合者（保留原文以便 DELETE）。 */
    List<String> resolveMatchedIds() {
        List<String> distinct = backtestRecordMapper.selectDistinctStrategyIds();
        Set<String> out = new LinkedHashSet<String>();
        if (distinct != null) {
            for (String raw : distinct) {
                if (!StringUtils.hasText(raw)) {
                    continue;
                }
                if (isRetired(raw)) {
                    out.add(raw.trim());
                }
            }
        }
        out.addAll(CANONICAL_DELETE_IDS);
        return new ArrayList<String>(out);
    }

    static boolean isRetired(String raw) {
        if (!StringUtils.hasText(raw)) {
            return false;
        }
        return RETIRED_IDS.contains(raw.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 若内存或 system_config 仍指向已下线策略，重置为 {@link StrategyRegistry#DEFAULT_ID}。
     */
    boolean resetRetiredActiveStrategy() {
        boolean reset = false;
        String memory = props != null ? props.getActiveStrategy() : null;
        if (isRetired(memory)) {
            props.setActiveStrategy(StrategyRegistry.DEFAULT_ID);
            reset = true;
        }
        LiveLedgerService ledger = ledgerProvider.getIfAvailable();
        if (ledger != null) {
            try {
                String stored = ledger.loadConfigOrNull(ActiveStrategyService.CONFIG_KEY);
                if (isRetired(stored)) {
                    ledger.saveConfig(ActiveStrategyService.CONFIG_KEY, StrategyRegistry.DEFAULT_ID,
                            "纸面激活策略（下线画像后重置为 maCross）");
                    if (props != null) {
                        props.setActiveStrategy(StrategyRegistry.DEFAULT_ID);
                    }
                    reset = true;
                }
            } catch (Exception e) {
                log.error("重置激活策略配置失败: {}", e.getMessage(), e);
            }
        }
        return reset;
    }
}
