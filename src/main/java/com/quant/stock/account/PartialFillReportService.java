package com.quant.stock.account;

import com.quant.stock.config.QuantProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部成率日报（P0-95）：对照委托量与成交量；不改金叉。
 */
@Service
public class PartialFillReportService {

    private final AccountOverviewService accountOverviewService;
    private final QuantProperties props;

    public PartialFillReportService(AccountOverviewService accountOverviewService, QuantProperties props) {
        this.accountOverviewService = accountOverviewService;
        this.props = props;
    }

    public Map<String, Object> dailyReport() {
        List<Map<String, Object>> orders = accountOverviewService.orders();
        int submittedLike = 0;
        int filledFull = 0;
        int partial = 0;
        int cancelled = 0;
        BigDecimal fillRateSum = BigDecimal.ZERO;
        int fillRateN = 0;
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();

        for (Map<String, Object> o : orders) {
            String status = str(o.get("status"));
            int vol = intVal(o.get("volume"));
            int filled = intVal(o.get("filledVolume"));
            if ("CANCELLED".equals(status) || "REJECTED".equals(status)) {
                cancelled++;
            }
            if ("PARTIAL".equals(status)) {
                partial++;
                submittedLike++;
            } else if ("FILLED".equals(status)) {
                submittedLike++;
                if (vol > 0 && filled >= vol) {
                    filledFull++;
                } else if (filled > 0 && filled < vol) {
                    partial++;
                } else {
                    filledFull++;
                }
            } else if ("SUBMITTED".equals(status)) {
                submittedLike++;
            }
            if (vol > 0 && ("FILLED".equals(status) || "PARTIAL".equals(status))) {
                BigDecimal rate = BigDecimal.valueOf(Math.min(filled, vol))
                        .divide(BigDecimal.valueOf(vol), 4, RoundingMode.HALF_UP);
                fillRateSum = fillRateSum.add(rate);
                fillRateN++;
                if (rate.compareTo(BigDecimal.ONE) < 0) {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("orderId", o.get("orderId"));
                    row.put("code", o.get("code"));
                    row.put("side", o.get("side"));
                    row.put("status", status);
                    row.put("volume", vol);
                    row.put("filledVolume", filled);
                    row.put("fillRate", rate);
                    rows.add(row);
                }
            }
        }

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("asOf", LocalDateTime.now().toString());
        m.put("tradeMode", props.getTradeMode());
        m.put("backtestFillRatio", props.getBacktestFillRatio());
        m.put("orderCount", orders.size());
        m.put("submittedLikeCount", submittedLike);
        m.put("fullFillCount", filledFull);
        m.put("partialCount", partial);
        m.put("cancelledOrRejected", cancelled);
        m.put("avgFillRate", fillRateN == 0 ? null
                : fillRateSum.divide(BigDecimal.valueOf(fillRateN), 4, RoundingMode.HALF_UP));
        m.put("partialRows", rows);
        m.put("hint", "P0-95 部成率日报；回测 fillRatio&lt;1 时买单可留残量；改价=撤补重置队尾（实盘 cancel）");
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int intVal(Object o) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }
}
