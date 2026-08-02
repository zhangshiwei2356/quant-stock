package com.quant.stock.session;

import com.quant.stock.market.BarPeriod;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 探测会话策略数据依赖是否可用。INDEX/AUCTION/ORDER_BOOK 当前本地无可靠源 → UNAVAILABLE。
 */
@Component
@RequiredArgsConstructor
public class SessionDepProbe {

    private final MarketDataService marketDataService;

    public Set<DataDep> probeUnavailable(String stockCode, LocalDateTime start, LocalDateTime end,
                                         Set<DataDep> required) {
        if (required == null || required.isEmpty()) {
            return Collections.emptySet();
        }
        EnumSet<DataDep> missing = EnumSet.noneOf(DataDep.class);
        for (DataDep dep : required) {
            if (!isAvailable(dep, stockCode, start, end)) {
                missing.add(dep);
            }
        }
        return missing;
    }

    public boolean isAvailable(DataDep dep, String stockCode, LocalDateTime start, LocalDateTime end) {
        if (dep == null) {
            return true;
        }
        switch (dep) {
            case MIN1:
                List<BarDTO> bars = marketDataService.getKline(stockCode, BarPeriod.MIN_1, start, end);
                return bars != null && !bars.isEmpty();
            case INDEX:
            case AUCTION:
            case ORDER_BOOK:
                // 本地未接入可靠源：固定 UNAVAILABLE（不做假填充）
                return false;
            default:
                return false;
        }
    }
}
