package com.quant.stock.strategy;

import com.quant.stock.admin.EffectiveParamsService;
import com.quant.stock.admin.ParamsScope;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.strategy.dto.TradeSignal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 金叉买卖逻辑副本：规则与 {@link MaCrossStrategy} 相同，过滤阈值来自固定 {@link MaCrossFilterProfile}，
 * 不读取全局 quant 过滤开关，便于多画像对照回测；原版策略类保持不动。
 * <p>
 * 若该策略 id 存在 {@code strategy_param} 稀疏包，则过滤改读生效快照（全局⊕稀疏）。
 */
public abstract class AbstractMaCrossProfileStrategy extends BaseStrategy {

    @Autowired
    @Lazy
    private EffectiveParamsService effectiveParamsService;

    @Autowired
    private QuantProperties quantProperties;

    protected abstract MaCrossFilterProfile profile();

    @Override
    public String name() {
        return profile().getId();
    }

    @Override
    public String fingerprintId() {
        return getClass().getSimpleName();
    }

    /** 回测下拉展示名。 */
    public String uiLabel() {
        return profile().getLabel();
    }

    public String profileSummary() {
        return profile().getSummary();
    }

    @Override
    public String detailIntro() {
        MaCrossFilterProfile p = profile();
        return p.getLabel() + "。买卖公式与主路径金叉相同（MA5/MA20），过滤包固定为画像参数，"
                + "不读全局 quant 过滤开关，便于回测下拉对照。"
                + "画像摘要：" + p.getSummary()
                + "。若存在 strategy_param 稀疏包，过滤改读生效快照（全局⊕稀疏）。";
    }

    @Override
    public TradeSignal calcSignal(String stockCode, List<BarDTO> closedBars) {
        if (closedBars == null || closedBars.size() < 65) {
            return TradeSignal.none(stockCode);
        }
        IndicatorSignalUtil.IndicatorBundle ind = IndicatorSignalUtil.precompute(closedBars);
        int i = ind.size - 1;
        Map<String, BigDecimal> latest = IndicatorSignalUtil.calcLatestIndicators(closedBars);
        BigDecimal close = latest.getOrDefault("close", closedBars.get(i).getClose());
        BigDecimal rsi = latest.getOrDefault("rsi14", BigDecimal.ZERO);
        BigDecimal atr = latest.getOrDefault("atr14", BigDecimal.ZERO);
        BigDecimal adx = latest.getOrDefault("adx14", BigDecimal.ZERO);
        MaCrossFilterProfile p = profile();

        if (IndicatorSignalUtil.isMaCrossDown(closedBars)) {
            return TradeSignal.builder()
                    .stockCode(stockCode)
                    .signalType(TradeSignal.Signal.SELL)
                    .suggestPrice(close)
                    .suggestVolume(0)
                    .signalDesc(String.format("[%s]死叉卖出 RSI=%.2f ADX=%.2f", p.getId(), rsi, adx))
                    .build();
        }

        if (IndicatorSignalUtil.isMaCrossUp(closedBars)) {
            String reject = rejectReason(ind, i);
            if (reject != null) {
                return TradeSignal.builder()
                        .stockCode(stockCode)
                        .signalType(TradeSignal.Signal.NONE)
                        .suggestPrice(close)
                        .suggestVolume(0)
                        .signalDesc("[" + p.getId() + "]金叉被过滤: " + reject)
                        .build();
            }
            return TradeSignal.builder()
                    .stockCode(stockCode)
                    .signalType(TradeSignal.Signal.BUY)
                    .suggestPrice(close)
                    .suggestVolume(0)
                    .signalDesc(String.format("[%s]金叉买入 RSI=%.2f ATR=%.4f ADX=%.2f",
                            p.getId(), rsi, atr, adx))
                    .build();
        }

        return TradeSignal.builder()
                .stockCode(stockCode)
                .signalType(TradeSignal.Signal.NONE)
                .suggestPrice(close)
                .suggestVolume(0)
                .signalDesc(String.format("[%s]观望 MA5=%.2f MA20=%.2f",
                        p.getId(), ind.ma5[i], ind.ma20[i]))
                .build();
    }

    public String rejectReason(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        if (effectiveParamsService != null && effectiveParamsService.hasSparse(name())) {
            QuantProperties qp = ParamsScope.current(effectiveParamsService.resolve(name()));
            if (qp.isTrendFilterEnabled() && !ind.isTrendUp(i)) {
                return "大周期MA60未向上";
            }
            if (qp.isVolumeFilterEnabled()
                    && !ind.isVolumeConfirm(i, qp.getVolumeConfirmRatio().doubleValue())) {
                return "无量金叉";
            }
            if (qp.isAdxFilterEnabled()
                    && !ind.isAdxTradable(i, qp.getAdxMin().doubleValue(), qp.getAdxChopMax().doubleValue())) {
                return "ADX震荡市或强度不足";
            }
            if (qp.getRsiBuyMax() != null
                    && qp.getRsiBuyMax().compareTo(new BigDecimal("100")) < 0
                    && !Double.isNaN(ind.rsi14[i])
                    && BigDecimal.valueOf(ind.rsi14[i]).compareTo(qp.getRsiBuyMax()) >= 0) {
                return "RSI过高";
            }
            if (!Double.isNaN(ind.atr14[i])
                    && BigDecimal.valueOf(ind.atr14[i]).compareTo(qp.getAtrMinThreshold()) <= 0) {
                return "ATR过低";
            }
            return null;
        }
        MaCrossFilterProfile p = profile();
        if (p.isTrendFilterEnabled() && !ind.isTrendUp(i)) {
            return "大周期MA60未向上";
        }
        if (p.isVolumeFilterEnabled()
                && !ind.isVolumeConfirm(i, p.getVolumeConfirmRatio().doubleValue())) {
            return "无量金叉";
        }
        if (p.isAdxFilterEnabled()
                && !ind.isAdxTradable(i, p.getAdxMin().doubleValue(), p.getAdxChopMax().doubleValue())) {
            return "ADX震荡市或强度不足";
        }
        if (p.getRsiBuyMax() != null
                && p.getRsiBuyMax().compareTo(new BigDecimal("100")) < 0
                && !Double.isNaN(ind.rsi14[i])
                && BigDecimal.valueOf(ind.rsi14[i]).compareTo(p.getRsiBuyMax()) >= 0) {
            return "RSI过高";
        }
        if (!Double.isNaN(ind.atr14[i])
                && BigDecimal.valueOf(ind.atr14[i]).compareTo(p.getAtrMinThreshold()) <= 0) {
            return "ATR过低";
        }
        return null;
    }

    @Override
    public boolean isBuySignalAt(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        if (!ind.isMaCrossUp(i)) {
            return false;
        }
        return rejectReason(ind, i) == null;
    }

    @Override
    public boolean isSellSignalAt(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        return ind.isMaCrossDown(i);
    }
}
