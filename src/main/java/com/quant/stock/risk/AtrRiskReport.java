package com.quant.stock.risk;

import com.quant.stock.backtest.DecisionAnalysisLog;
import com.quant.stock.config.QuantProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ATR 止损/定仓一体契约快照（P0-108）：供回测结果验收字段，不改开仓逻辑。
 * <ul>
 *   <li>定仓：单票上限 × ATR调节(baseAtr/atr，夹紧 0.2~1.5) × 金字塔</li>
 *   <li>止损线：max(成本−atrMult×ATR, 成本−权益×hardPct/股数)，只上移</li>
 *   <li>移动止盈：最高价−trailMult×ATR，只上移</li>
 * </ul>
 */
public final class AtrRiskReport {

    private AtrRiskReport() {
    }

    /** 从回测配置与分析日志生成 ATR 风控契约快照。 */
    public static Map<String, Object> from(QuantProperties p, DecisionAnalysisLog analysis) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (p == null) {
            return m;
        }
        m.put("stopLossEnabled", p.isStopLossEnabled());
        m.put("atrStopMultiplier", p.getAtrStopMultiplier());
        m.put("hardStopCapitalPct", p.getHardStopCapitalPct());
        m.put("trailingStopEnabled", p.isTrailingStopEnabled());
        m.put("trailingAtrMultiplier", p.getTrailingAtrMultiplier());
        m.put("baseAtr", p.getBaseAtr());
        m.put("atrAdjustClampMin", new BigDecimal("0.2"));
        m.put("atrAdjustClampMax", new BigDecimal("1.5"));
        m.put("maxSinglePosition", p.getMaxSinglePosition());
        m.put("pyramid", p.getPyramidFirst() + "/" + p.getPyramidSecond() + "/" + p.getPyramidThird());
        m.put("stopExitEvents", analysis == null ? 0 : analysis.stopCount());
        m.put("contract", "ATR定仓+ATR/硬止损线+移动止盈；止损只上移；不改金叉");
        return m;
    }
}
