package com.quant.stock.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单股回测结果：绩效指标、权益曲线、成交明细与决策分析事件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackTestResult {

    /** 标的代码 */
    private String stockCode;
    /** 初始资金 */
    private BigDecimal initCapital;
    /** 期末总资产（现金+持仓市值） */
    private BigDecimal finalAsset;
    /** 总收益率 */
    private BigDecimal totalRate;
    /** 最大回撤 */
    private BigDecimal maxDrawDown;
    /** 成交笔数（买卖各计一笔） */
    private Integer totalTradeNum;
    /** 胜率（完整回合盈利占比） */
    private BigDecimal winRate;
    /** 成交流水 */
    private List<BackTradeRecord> trades;
    /** 权益曲线时间轴 */
    private List<String> equityTimes;
    /** 权益曲线数值 */
    private List<BigDecimal> equityCurve;
    /** K 线买点标记 */
    private List<MarkPoint> buyMarks;
    /** K 线卖点标记 */
    private List<MarkPoint> sellMarks;
    /** 本次回测决策分析事件（为何买卖、看了哪些数据、买多少） */
    private List<AnalysisEvent> analysisEvents;
    private String analysisSummary;
    /** 策略相关配置指纹（P0-93，格式 v1:&lt;16hex&gt;） */
    private String configFingerprint;
    /** ATR 止损/定仓一体契约快照（P0-108） */
    private Map<String, Object> atrRisk;
    /**
     * 回测引擎：{@code classic}（默认金叉五步）或 {@code session}（分钟三分支旁路）。
     * 经典路径可空，前端按 classic 展示。
     */
    private String engine;
    /** session 引擎因缺依赖降级的分支名（OPEN/MID/CLOSE） */
    private List<String> degradedBranches;
    /** session 引擎原始事件（脚手架/调试）；经典路径为空 */
    private List<com.quant.stock.session.SessionEvent> sessionEvents;
    /**
     * session 分分支统计：各分支 tick/买卖笔数/金额/已实现盈亏，以及 sessionDays、fillMode 等。
     */
    private Map<String, Object> sessionBranchStats;

    /** 图表买卖点坐标 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MarkPoint {
        private String time;
        private BigDecimal price;
    }

    /** 构造 K 线不足或参数无效时的空结果 */
    public static BackTestResult empty(String code, BigDecimal init) {
        return BackTestResult.builder()
                .stockCode(code)
                .initCapital(init)
                .finalAsset(init)
                .totalRate(BigDecimal.ZERO)
                .maxDrawDown(BigDecimal.ZERO)
                .totalTradeNum(0)
                .winRate(BigDecimal.ZERO)
                .trades(new ArrayList<BackTradeRecord>())
                .equityTimes(new ArrayList<String>())
                .equityCurve(new ArrayList<BigDecimal>())
                .buyMarks(new ArrayList<MarkPoint>())
                .sellMarks(new ArrayList<MarkPoint>())
                .analysisEvents(new ArrayList<AnalysisEvent>())
                .analysisSummary("")
                .atrRisk(new LinkedHashMap<String, Object>())
                .engine("classic")
                .degradedBranches(new ArrayList<String>())
                .sessionEvents(new ArrayList<com.quant.stock.session.SessionEvent>())
                .sessionBranchStats(new LinkedHashMap<String, Object>())
                .build();
    }
}
