package com.quant.stock.strategy;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.strategy.dto.TradeSignal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 均线金叉死叉 + 三重过滤（MA60趋势 / 放量 / ADX）
 */
@Component
@RequiredArgsConstructor
public class MaCrossStrategy extends BaseStrategy {

    private final QuantProperties quantProperties;

    @Override
    public String name() {
        return "MA_CROSS_FILTERED";
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

        if (IndicatorSignalUtil.isMaCrossDown(closedBars)) {
            return TradeSignal.builder()
                    .stockCode(stockCode)
                    .signalType(TradeSignal.Signal.SELL)
                    .suggestPrice(close)
                    .suggestVolume(0)
                    .signalDesc(String.format("死叉卖出 RSI=%.2f ADX=%.2f", rsi, adx))
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
                        .signalDesc("金叉被过滤: " + reject)
                        .build();
            }
            return TradeSignal.builder()
                    .stockCode(stockCode)
                    .signalType(TradeSignal.Signal.BUY)
                    .suggestPrice(close)
                    .suggestVolume(0)
                    .signalDesc(String.format("金叉买入(过滤通过) RSI=%.2f ATR=%.4f ADX=%.2f", rsi, atr, adx))
                    .build();
        }

        return TradeSignal.builder()
                .stockCode(stockCode)
                .signalType(TradeSignal.Signal.NONE)
                .suggestPrice(close)
                .suggestVolume(0)
                .signalDesc(String.format("观望 MA5=%.2f MA20=%.2f MA60=%.2f ADX=%.2f",
                        ind.ma5[i], ind.ma20[i],
                        Double.isNaN(ind.ma60[i]) ? 0 : ind.ma60[i],
                        Double.isNaN(ind.adx14[i]) ? 0 : ind.adx14[i]))
                .build();
    }

    public String rejectReason(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        if (quantProperties.isTrendFilterEnabled() && !ind.isTrendUp(i)) {
            return "大周期MA60未向上";
        }
        if (quantProperties.isVolumeFilterEnabled()
                && !ind.isVolumeConfirm(i, quantProperties.getVolumeConfirmRatio().doubleValue())) {
            return "无量金叉";
        }
        if (quantProperties.isAdxFilterEnabled()
                && !ind.isAdxTradable(i,
                quantProperties.getAdxMin().doubleValue(),
                quantProperties.getAdxChopMax().doubleValue())) {
            return "ADX震荡市或强度不足";
        }
        if (quantProperties.getRsiBuyMax() != null
                && quantProperties.getRsiBuyMax().compareTo(new BigDecimal("100")) < 0
                && !Double.isNaN(ind.rsi14[i])
                && BigDecimal.valueOf(ind.rsi14[i]).compareTo(quantProperties.getRsiBuyMax()) >= 0) {
            return "RSI过高";
        }
        if (!Double.isNaN(ind.atr14[i])
                && BigDecimal.valueOf(ind.atr14[i]).compareTo(quantProperties.getAtrMinThreshold()) <= 0) {
            return "ATR过低";
        }
        return null;
    }

    public boolean isBuySignalAt(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        if (!ind.isMaCrossUp(i)) {
            return false;
        }
        return rejectReason(ind, i) == null;
    }
}
