package com.quant.stock.backtest;

import com.quant.stock.mapper.BacktestRecordMapper;
import com.quant.stock.strategy.StrategyIdAliases;
import com.quant.stock.strategy.StrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 补全 {@code bt_backtest_record.strategy_id}，使策略管理「回测历史」可按注册策略关联查询。
 * <ul>
 *   <li>空白 → 默认 {@link StrategyRegistry#DEFAULT_ID}（历史主路径金叉）</li>
 *   <li>指纹/旧名（如 MaCrossStrategy）→ 注册 id（maCross）</li>
 *   <li>大小写不规范 → 注册表规范 name()</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class BacktestStrategyIdBackfillService {

    private final BacktestRecordMapper backtestRecordMapper;
    private final StrategyRegistry strategyRegistry;

    /** 启动后自动补全一次（幂等）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            Map<String, Object> r = backfill();
            log.info("回测 strategy_id 补全: {}", r);
        } catch (Exception e) {
            log.warn("回测 strategy_id 补全失败: {}", e.getMessage());
        }
    }

    /**
     * 执行补全；可运维手动再跑。
     *
     * @return blankUpdated / aliasUpdated / caseNormalized / unknownBefore / unknownAfter / leftoverIds
     */
    public Map<String, Object> backfill() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        long before = backtestRecordMapper.countUnknownStrategy();
        out.put("unknownBefore", before);

        String defaultId = StrategyRegistry.DEFAULT_ID;
        if (!strategyRegistry.contains(defaultId)) {
            List<String> ids = strategyRegistry.ids();
            defaultId = ids.isEmpty() ? "maCross" : ids.get(0);
        } else {
            defaultId = strategyRegistry.resolve(defaultId).name();
        }

        int blank = backtestRecordMapper.updateBlankStrategyId(defaultId);
        out.put("blankUpdated", blank);
        out.put("defaultStrategyId", defaultId);

        int aliasTotal = 0;
        // 金叉历史别名 → maCross
        List<String> maAliases = new ArrayList<String>();
        for (Map.Entry<String, String> e : StrategyIdAliases.aliasMap().entrySet()) {
            if ("maCross".equalsIgnoreCase(e.getValue())) {
                // 用常见原文形式一并更新
                maAliases.add(e.getKey());
            }
        }
        Collections.addAll(maAliases,
                "MaCrossStrategy", "MA_CROSS_FILTERED", "MaCross", "MACROSS", "ma_cross");
        String maCanon = strategyRegistry.contains("maCross")
                ? strategyRegistry.resolve("maCross").name() : "maCross";
        // 去掉已是规范 id 的
        List<String> fromMa = new ArrayList<String>();
        for (String a : maAliases) {
            if (a != null && !a.equals(maCanon)) {
                fromMa.add(a);
            }
        }
        if (!fromMa.isEmpty()) {
            aliasTotal += backtestRecordMapper.updateStrategyIdAliases(fromMa, maCanon);
        }

        // 其它 distinct：能解析到注册表则规范大小写
        int caseNorm = 0;
        List<String> distinct = backtestRecordMapper.selectDistinctStrategyIds();
        if (distinct == null) {
            distinct = Collections.emptyList();
        }
        List<String> leftover = new ArrayList<String>();
        for (String raw : distinct) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String canon = StrategyIdAliases.toCanonical(raw, strategyRegistry);
            if (canon != null && strategyRegistry.contains(canon)) {
                String official = strategyRegistry.resolve(canon).name();
                if (!official.equals(raw)) {
                    caseNorm += backtestRecordMapper.updateStrategyIdAliases(
                            Collections.singletonList(raw), official);
                }
            } else if (!strategyRegistry.contains(raw)) {
                leftover.add(raw);
            }
        }
        out.put("aliasUpdated", aliasTotal);
        out.put("caseNormalized", caseNorm);
        out.put("leftoverIds", leftover);

        long after = backtestRecordMapper.countUnknownStrategy();
        out.put("unknownAfter", after);
        out.put("ok", true);
        return out;
    }
}
