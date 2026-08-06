package com.quant.stock.account;

import com.quant.stock.config.ConfigFingerprint;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.kuangrui.OesReadonlyService;
import com.quant.stock.pool.TradePoolService;
import com.quant.stock.trade.TradeCostModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 纸面-实盘差异对账闸（P0-91）：成本残差 / 部成闪烁 / 选股池漂移 / 撮合假设。
 * 不改金叉主路径；真券商对账仍依赖外部 API。
 */
@Service
public class PaperLiveReconcileService {

    private final QuantProperties props;
    private final TradeCostModel tradeCostModel;
    private final AccountOverviewService accountOverviewService;
    private final SlippageResidualService slippageResidualService;
    private final ObjectProvider<TradePoolService> tradePoolProvider;
    private final ObjectProvider<OesReadonlyService> oesReadonlyProvider;

    public PaperLiveReconcileService(QuantProperties props,
                                     TradeCostModel tradeCostModel,
                                     AccountOverviewService accountOverviewService,
                                     SlippageResidualService slippageResidualService,
                                     ObjectProvider<TradePoolService> tradePoolProvider,
                                     ObjectProvider<OesReadonlyService> oesReadonlyProvider) {
        this.props = props;
        this.tradeCostModel = tradeCostModel;
        this.accountOverviewService = accountOverviewService;
        this.slippageResidualService = slippageResidualService;
        this.tradePoolProvider = tradePoolProvider;
        this.oesReadonlyProvider = oesReadonlyProvider;
    }

    /** 生成纸面与实盘假设差异对账报告（费用、部成、目标池、撮合模式等） */
    public Map<String, Object> report() {
        List<Map<String, Object>> orders = accountOverviewService.orders();
        List<Map<String, Object>> positions = accountOverviewService.positions();
        List<Map<String, Object>> gaps = new ArrayList<Map<String, Object>>();

        int filled = 0;
        int partial = 0;
        int cancelledOrRejected = 0;
        int sameDayFillVsNextBar = 0;
        BigDecimal feeResidualSum = BigDecimal.ZERO;
        BigDecimal feeResidualAbsMax = BigDecimal.ZERO;
        int costWarnCount = 0;
        BigDecimal feeWarn = new BigDecimal("1.00");

        List<Map<String, Object>> costRows = new ArrayList<Map<String, Object>>();

        for (Map<String, Object> o : orders) {
            String status = str(o.get("status"));
            if ("FILLED".equals(status)) {
                filled++;
            } else if ("PARTIAL".equals(status)) {
                partial++;
                gaps.add(gap("FLICKER", "WARN", str(o.get("code")),
                        "部成未完结", "filled=" + o.get("filledVolume") + "/" + o.get("volume"),
                        o.get("orderId")));
            } else if ("CANCELLED".equals(status) || "REJECTED".equals(status)) {
                cancelledOrRejected++;
                gaps.add(gap("FLICKER", "INFO", str(o.get("code")),
                        "委托终止", status, o.get("orderId")));
            }

            String signalDate = str(o.get("signalDate"));
            String execDate = str(o.get("executionDate"));
            if (props.isNextBarOpenFill()
                    && !signalDate.isEmpty() && !execDate.isEmpty()
                    && signalDate.equals(execDate)
                    && ("FILLED".equals(status) || "PARTIAL".equals(status))) {
                sameDayFillVsNextBar++;
                gaps.add(gap("FILL_ASSUMPTION", "WARN", str(o.get("code")),
                        "信号日=成交日", "纸面默认次日开盘撮合，本笔同日成交", o.get("orderId")));
            }

            if ("FILLED".equals(status) || "PARTIAL".equals(status)) {
                Map<String, Object> cost = costCompare(o);
                if (cost != null) {
                    costRows.add(cost);
                    BigDecimal residual = (BigDecimal) cost.get("feeResidual");
                    if (residual != null) {
                        feeResidualSum = feeResidualSum.add(residual);
                        BigDecimal abs = residual.abs();
                        if (abs.compareTo(feeResidualAbsMax) > 0) {
                            feeResidualAbsMax = abs;
                        }
                        if (abs.compareTo(feeWarn) >= 0) {
                            costWarnCount++;
                            gaps.add(gap("COST", "WARN", str(o.get("code")),
                                    "费用残差超阈", "residual=" + residual + " 阈=" + feeWarn,
                                    o.get("orderId")));
                        }
                    }
                    BigDecimal pxBps = (BigDecimal) cost.get("priceResidualBps");
                    if (pxBps != null && pxBps.abs().compareTo(new BigDecimal("20")) >= 0) {
                        gaps.add(gap("COST", "WARN", str(o.get("code")),
                                "成交价相对委托偏离≥20bp", "bps=" + pxBps, o.get("orderId")));
                    }
                }
            }
        }

        // 选股：持仓/成交代码不在活跃目标池
        Set<String> pool = new HashSet<String>();
        TradePoolService poolSvc = tradePoolProvider.getIfAvailable();
        if (poolSvc != null) {
            pool.addAll(poolSvc.listActiveCodes());
        }
        if (!pool.isEmpty()) {
            for (Map<String, Object> p : positions) {
                String code = str(p.get("code"));
                if (!code.isEmpty() && !pool.contains(code)) {
                    gaps.add(gap("SELECTION", "WARN", code,
                            "持仓不在活跃目标池", "poolSize=" + pool.size(), null));
                }
            }
            for (Map<String, Object> o : orders) {
                String code = str(o.get("code"));
                String status = str(o.get("status"));
                if (("FILLED".equals(status) || "PARTIAL".equals(status) || "SUBMITTED".equals(status))
                        && !code.isEmpty() && !pool.contains(code)) {
                    gaps.add(gap("SELECTION", "INFO", code,
                            "委托代码不在活跃目标池", status, o.get("orderId")));
                }
            }
        } else {
            gaps.add(gap("SELECTION", "INFO", null,
                    "目标池为空或未启用库表", "无法做选股对账", null));
        }

        // 模式闸门
        boolean brokerReady = false; // 真柜台对账待 API
        String mode = props.getTradeMode() == null ? "sim" : props.getTradeMode();
        if (!"sim".equalsIgnoreCase(mode) && !brokerReady) {
            gaps.add(gap("MODE", "WARN", null,
                    "tradeMode=sdk 但券商对账未接入", "仍为本地账本/桩", null));
        }
        gaps.add(gap("MODE", "INFO", null,
                "纸面≠冲击基准", "本地 sim/sdk 桩只验机械链路；冲击用成本模型近似", null));

        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("orderCount", orders.size());
        summary.put("filledCount", filled);
        summary.put("partialCount", partial);
        summary.put("cancelledOrRejected", cancelledOrRejected);
        summary.put("sameDayFillVsNextBar", sameDayFillVsNextBar);
        summary.put("feeResidualSum", feeResidualSum.setScale(2, RoundingMode.HALF_UP));
        summary.put("feeResidualAbsMax", feeResidualAbsMax.setScale(2, RoundingMode.HALF_UP));
        summary.put("costWarnCount", costWarnCount);
        summary.put("gapCount", gaps.size());
        summary.put("activePoolSize", pool.size());
        summary.put("positionCount", positions.size());

        boolean gatePass = costWarnCount == 0 && sameDayFillVsNextBar == 0 && partial == 0
                && !"sdk".equalsIgnoreCase(mode);

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("asOf", LocalDateTime.now().toString());
        m.put("tradeMode", mode);
        m.put("nextBarOpenFill", props.isNextBarOpenFill());
        m.put("configFingerprint", ConfigFingerprint.of(props));
        m.put("brokerReconcileAvailable", brokerReady);
        m.put("gatePass", gatePass);
        m.put("gateHint", gatePass
                ? "本地机械对账未见明显红灯（非真柜台验收）"
                : "存在费用/同日成交/部成/sdk模式告警，详见 gaps");
        Map<String, Object> slip = slippageResidualService.dailyReport();
        summary.put("avgAbsAdverseBps", slip.get("avgAbsAdverseBps"));
        summary.put("slippageSampleCount", slip.get("sampleCount"));
        summary.put("recalibrationHint", slip.get("recalibrationHint"));

        m.put("summary", summary);
        m.put("costRows", costRows);
        m.put("gaps", gaps);
        m.put("slippageResidual", slip);
        m.put("dimensions", dimLegend(oesLive()));
        m.put("brokerApi", oesBrokerMeta());
        return m;
    }

    private Map<String, Object> costCompare(Map<String, Object> o) {
        BigDecimal filledPrice = bd(o.get("filledPrice"));
        if (filledPrice.compareTo(BigDecimal.ZERO) <= 0) {
            filledPrice = bd(o.get("price"));
        }
        int filledVol = intVal(o.get("filledVolume"));
        if (filledVol <= 0) {
            filledVol = intVal(o.get("volume"));
        }
        if (filledVol <= 0 || filledPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal amount = filledPrice.multiply(BigDecimal.valueOf(filledVol));
        String side = str(o.get("side"));
        BigDecimal modelFee = "SELL".equalsIgnoreCase(side)
                ? tradeCostModel.sellFee(amount) : tradeCostModel.buyFee(amount);
        BigDecimal actualFee = bd(o.get("fee"));
        BigDecimal feeResidual = actualFee.subtract(modelFee);
        BigDecimal orderPrice = bd(o.get("price"));
        BigDecimal priceResidualBps = BigDecimal.ZERO;
        if (orderPrice.compareTo(BigDecimal.ZERO) > 0) {
            priceResidualBps = filledPrice.subtract(orderPrice)
                    .divide(orderPrice, 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("10000"))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("orderId", o.get("orderId"));
        row.put("code", o.get("code"));
        row.put("side", side);
        row.put("filledPrice", filledPrice);
        row.put("orderPrice", orderPrice);
        row.put("filledVolume", filledVol);
        row.put("actualFee", actualFee);
        row.put("modelFee", modelFee);
        row.put("feeResidual", feeResidual.setScale(2, RoundingMode.HALF_UP));
        row.put("priceResidualBps", priceResidualBps);
        row.put("signalDate", o.get("signalDate"));
        row.put("executionDate", o.get("executionDate"));
        return row;
    }

    private static Map<String, Object> gap(String dim, String severity, String code,
                                           String title, String detail, Object orderId) {
        Map<String, Object> g = new LinkedHashMap<String, Object>();
        g.put("dimension", dim);
        g.put("severity", severity);
        g.put("code", code);
        g.put("title", title);
        g.put("detail", detail);
        g.put("orderId", orderId);
        g.put("asOf", LocalDate.now().toString());
        return g;
    }

    private static List<Map<String, String>> dimLegend(boolean oesLive) {
        List<Map<String, String>> list = new ArrayList<Map<String, String>>();
        list.add(dim("FLICKER", "闪烁/部成/废单", "CANCELLED/REJECTED/PARTIAL"));
        list.add(dim("COST", "成本残差", "实际费用/成交价 vs TradeCostModel"));
        list.add(dim("SELECTION", "选股漂移", "持仓/委托不在活跃目标池"));
        list.add(dim("FILL_ASSUMPTION", "撮合假设", "次日开盘 vs 同日成交"));
        list.add(dim("MODE", "模式闸门", "sim/sdk 与真柜台对账可用性"));
        list.add(dim("ENGINE_PATH", "引擎路径差",
                "回测有部成/压力/结构突变；实盘另有换手/IC衰减；组合已对齐部成+AUM/POV+压力降仓"));
        list.add(dim("BROKER_API", "真柜台对账",
                oesLive
                        ? "AVAILABLE（OES 只读 M2：/api/ops/kuangrui/oes/reconcile）"
                        : "UNAVAILABLE（待开启 quant.kuangrui.oes 或 -Pkuangrui）"));
        return list;
    }

    private boolean oesLive() {
        OesReadonlyService oes = oesReadonlyProvider == null ? null : oesReadonlyProvider.getIfAvailable();
        return oes != null && oes.isLive();
    }

    private Map<String, Object> oesBrokerMeta() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        OesReadonlyService oes = oesReadonlyProvider == null ? null : oesReadonlyProvider.getIfAvailable();
        if (oes == null) {
            m.put("live", false);
            m.put("hint", "OES 服务未装配");
            return m;
        }
        m.put("live", oes.isLive());
        m.putAll(oes.status());
        return m;
    }

    private static Map<String, String> dim(String id, String name, String desc) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("id", id);
        m.put("name", name);
        m.put("desc", desc);
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
        if (o == null) {
            return 0;
        }
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
