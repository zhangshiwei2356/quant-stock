package com.quant.stock.strategy;

import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.strategy.dto.TradeSignal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 金叉买卖逻辑副本：规则与 {@link MaCrossStrategy} 相同，过滤阈值来自固定 {@link MaCrossFilterProfile}，
 * 不读取全局 quant 过滤开关，便于多画像对照回测；原版策略类保持不动。
 */
public abstract class AbstractMaCrossProfileStrategy extends BaseStrategy {

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
