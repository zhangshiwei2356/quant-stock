package com.quant.stock.account;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.trade.TradeCostModel;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纸面滑点/费用残差日报（P0-99）：相对委托价与 TradeCostModel 费用模型。
 * 不回写改价；只出报告供校准决策。
 */
@Service
public class SlippageResidualService {

    private final AccountOverviewService accountOverviewService;
    private final TradeCostModel tradeCostModel;
    private final QuantProperties props;

    public SlippageResidualService(AccountOverviewService accountOverviewService,
                                   TradeCostModel tradeCostModel,
                                   QuantProperties props) {
        this.accountOverviewService = accountOverviewService;
        this.tradeCostModel = tradeCostModel;
        this.props = props;
    }

    /** 成交价/费用相对模型与委托价的残差日报 */
    public Map<String, Object> dailyReport() {
        List<Map<String, Object>> orders = accountOverviewService.orders();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        BigDecimal feeResSum = BigDecimal.ZERO;
        BigDecimal bpsAbsSum = BigDecimal.ZERO;
        int n = 0;
        int buyN = 0;
        int sellN = 0;
        for (Map<String, Object> o : orders) {
            String status = str(o.get("status"));
            if (!"FILLED".equals(status) && !"PARTIAL".equals(status)) {
                continue;
            }
            BigDecimal orderPx = bd(o.get("price"));
            BigDecimal fillPx = bd(o.get("filledPrice"));
            if (fillPx.compareTo(BigDecimal.ZERO) <= 0) {
                fillPx = orderPx;
            }
            int vol = intVal(o.get("filledVolume"));
            if (vol <= 0) {
                vol = intVal(o.get("volume"));
            }
            if (vol <= 0 || orderPx.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String side = str(o.get("side"));
            BigDecimal amount = fillPx.multiply(BigDecimal.valueOf(vol));
            BigDecimal modelFee = "SELL".equalsIgnoreCase(side)
                    ? tradeCostModel.sellFee(amount) : tradeCostModel.buyFee(amount);
            BigDecimal actualFee = bd(o.get("fee"));
            BigDecimal feeRes = actualFee.subtract(modelFee);
            BigDecimal bps = fillPx.subtract(orderPx)
                    .divide(orderPx, 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("10000"))
                    .setScale(2, RoundingMode.HALF_UP);
            // 买：正 bps=更贵（不利）；卖：负 bps=更低（不利）→ 统一不利为正
            BigDecimal adverseBps = "SELL".equalsIgnoreCase(side) ? bps.negate() : bps;

            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("orderId", o.get("orderId"));
            row.put("code", o.get("code"));
            row.put("side", side);
            row.put("orderPrice", orderPx);
            row.put("filledPrice", fillPx);
            row.put("priceResidualBps", bps);
            row.put("adverseBps", adverseBps);
            row.put("actualFee", actualFee);
            row.put("modelFee", modelFee);
            row.put("feeResidual", feeRes.setScale(2, RoundingMode.HALF_UP));
            row.put("executionDate", o.get("executionDate"));
            rows.add(row);

            feeResSum = feeResSum.add(feeRes);
            bpsAbsSum = bpsAbsSum.add(adverseBps.abs());
            n++;
            if ("SELL".equalsIgnoreCase(side)) {
                sellN++;
            } else {
                buyN++;
            }
        }

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("asOf", LocalDateTime.now().toString());
        m.put("tradeMode", props.getTradeMode());
        m.put("sampleCount", n);
        m.put("buyCount", buyN);
        m.put("sellCount", sellN);
        m.put("feeResidualSum", feeResSum.setScale(2, RoundingMode.HALF_UP));
        m.put("avgAbsAdverseBps", n == 0 ? null
                : bpsAbsSum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP));
        m.put("recalibrationHint", n >= 10 && bpsAbsSum.divide(BigDecimal.valueOf(Math.max(n, 1)), 2, RoundingMode.HALF_UP)
                .compareTo(new BigDecimal("15")) >= 0
                ? "平均不利价差≥15bp，建议复核 slip/impact 分段或扩容降频"
                : "样本不足或残差温和；不自动回写改价");
        m.put("rows", rows);
        m.put("hint", "P0-99 残差日报：对照委托价与费用模型；不静默改金叉/滑点配置");
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static BigDecimal bd(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal) {
            return (BigDecimal) o;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
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
