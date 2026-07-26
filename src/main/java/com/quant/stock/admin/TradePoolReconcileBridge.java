package com.quant.stock.admin;

import com.quant.stock.pool.TradePoolService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DataReconcileGate → TradePool 的窄桥（P0-107）。
 */
@Component
@ConditionalOnBean(TradePoolService.class)
public class TradePoolReconcileBridge implements DataReconcileGateService.TradePoolServiceBridge {

    private final TradePoolService tradePoolService;

    public TradePoolReconcileBridge(TradePoolService tradePoolService) {
        this.tradePoolService = tradePoolService;
    }

    /** 优先活跃目标池代码，否则退回全市场 universe */
    @Override
    public List<String> activeOrUniverseCodes() {
        List<String> codes = new ArrayList<String>(tradePoolService.listActiveCodes());
        if (!codes.isEmpty()) {
            return codes;
        }
        for (Map<String, String> u : tradePoolService.listUniverse()) {
            if (u.get("code") != null) {
                codes.add(u.get("code"));
            }
        }
        return codes;
    }
}
