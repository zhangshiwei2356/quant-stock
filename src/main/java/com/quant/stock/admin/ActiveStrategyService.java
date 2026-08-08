package com.quant.stock.admin;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.strategy.BaseStrategy;
import com.quant.stock.strategy.StrategyRegistry;
import com.quant.stock.trade.LiveLedgerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纸面激活策略热切换：写 {@code system_config(quant.active-strategy)} 并更新 {@link QuantProperties}。
 * 不影响回测下拉「仅本次」策略选择。
 */
@Slf4j
@Service
public class ActiveStrategyService {

    public static final String CONFIG_KEY = "quant.active-strategy";

    private final QuantProperties props;
    private final StrategyRegistry strategyRegistry;
    private final ObjectProvider<LiveLedgerService> ledgerProvider;

    public ActiveStrategyService(QuantProperties props,
                                 StrategyRegistry strategyRegistry,
                                 ObjectProvider<LiveLedgerService> ledgerProvider) {
        this.props = props;
        this.strategyRegistry = strategyRegistry;
        this.ledgerProvider = ledgerProvider;
    }

    /** 启动时若库内有覆盖键，则热加载到 QuantProperties。 */
    @EventListener(ApplicationReadyEvent.class)
    public void loadFromDbOnReady() {
        LiveLedgerService ledger = ledgerProvider.getIfAvailable();
        if (ledger == null) {
            return;
        }
        try {
            String stored = ledger.loadConfigOrNull(CONFIG_KEY);
            if (stored == null || stored.trim().isEmpty()) {
                return;
            }
            String id = stored.trim();
            if (!strategyRegistry.contains(id)) {
                log.warn("忽略未知激活策略 system_config {}={}", CONFIG_KEY, id);
                return;
            }
            String resolved = strategyRegistry.resolve(id).name();
            props.setActiveStrategy(resolved);
            log.info("已从 system_config 加载激活策略: {}", resolved);
        } catch (Exception e) {
            log.error("加载激活策略失败: {}", e.getMessage(), e);
        }
    }

    /** 已注册策略列表 + 当前激活 id（供运维页）。 */
    public Map<String, Object> listView() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (String id : strategyRegistry.ids()) {
            BaseStrategy s = strategyRegistry.resolve(id);
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", s.name());
            row.put("fingerprintId", s.fingerprintId());
            row.put("label", s.uiLabel());
            row.put("session", s instanceof com.quant.stock.session.SessionStrategy);
            String summary = s.profileSummary();
            if (summary != null && !summary.isEmpty()) {
                row.put("summary", summary);
            }
            list.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("activeStrategy", strategyRegistry.active().name());
        m.put("strategies", list);
        m.put("hint", "切换仅影响纸面扫描/扫池；回测下拉「仅本次」不受影响。须 confirm=true。");
        return m;
    }

    /**
     * 切换激活策略。
     *
     * @param strategyId 注册表 id
     * @param confirm    必须为 true
     */
    public Map<String, Object> switchActive(String strategyId, boolean confirm) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (!confirm) {
            m.put("ok", false);
            m.put("message", "请确认切换：confirm 须为 true（影响纸面扫描，不影响回测下拉）");
            return m;
        }
        if (strategyId == null || strategyId.trim().isEmpty()) {
            m.put("ok", false);
            m.put("message", "strategyId 不能为空");
            return m;
        }
        String id = strategyId.trim();
        if (!strategyRegistry.contains(id)) {
            m.put("ok", false);
            m.put("message", "未知策略 id=" + id + "，已知=" + strategyRegistry.ids());
            return m;
        }
        String resolved = strategyRegistry.resolve(id).name();
        String prev = props.getActiveStrategy();
        props.setActiveStrategy(resolved);
        LiveLedgerService ledger = ledgerProvider.getIfAvailable();
        if (ledger != null) {
            ledger.saveConfig(CONFIG_KEY, resolved, "纸面激活策略（运维热切换）");
        }
        log.info("激活策略已切换 {} → {}", prev, resolved);
        m.put("ok", true);
        m.put("previous", prev);
        m.put("activeStrategy", resolved);
        m.put("message", "已切换纸面激活策略为 " + resolved
                + (ledger == null ? "（未落库：db 未启用）" : "（已写入 system_config）"));
        return m;
    }
}
