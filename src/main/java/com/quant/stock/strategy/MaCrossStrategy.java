package com.quant.stock.strategy;

import com.quant.stock.admin.ParamsScope;
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

    /** 策略稳定 id（配置 quant.active-strategy）。 */
    @Override
    public String name() {
        return "maCross";
    }

    /** 保持历史指纹字段 strategy=MaCrossStrategy，避免默认配置下指纹漂移。 */
    @Override
    public String fingerprintId() {
        return "MaCrossStrategy";
    }

    @Override
    public String uiLabel() {
        return "均线金叉（maCross·读全局quant过滤）";
    }

    @Override
    public String profileSummary() {
        return "买卖=MA5/MA20；过滤开关读 application.yml";
    }

    @Override
    public String detailIntro() {
        return "默认主路径金叉策略（maCross）。买卖信号为 MA5/MA20 金叉买入、死叉卖出；"
                + "趋势(MA60)、放量、ADX/RSI 等过滤开关与阈值读取全局 quant 配置（application.yml / 运维改参）。"
                + "纸面扫池与默认回测均以此为激活策略；对照画像 maCrossBalanced 为固定过滤副本，不改本类行为。";
    }

    /**
     * 在已收盘 K 线上计算金叉/死叉及可选过滤后的买卖信号。
     *
     * @param stockCode  标的代码
     * @param closedBars 按时间升序的已收盘 bar
     */
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

    /**
     * 金叉买入被过滤时的原因；通过则返回 null。
     */
    public String rejectReason(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        QuantProperties qp = ParamsScope.current(quantProperties);
        if (qp.isTrendFilterEnabled() && !ind.isTrendUp(i)) {
            return "大周期MA60未向上";
        }
        if (qp.isVolumeFilterEnabled()
                && !ind.isVolumeConfirm(i, qp.getVolumeConfirmRatio().doubleValue())) {
            return "无量金叉";
        }
        if (qp.isAdxFilterEnabled()
                && !ind.isAdxTradable(i,
                qp.getAdxMin().doubleValue(),
                qp.getAdxChopMax().doubleValue())) {
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

    /**
     * 指定 bar 是否为「过滤通过」的金叉买入信号。
     */
    @Override
    public boolean isBuySignalAt(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        if (!ind.isMaCrossUp(i)) {
            return false;
        }
        return rejectReason(ind, i) == null;
    }

    /** 指定 bar 是否为死叉卖出信号。 */
    @Override
    public boolean isSellSignalAt(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        return ind.isMaCrossDown(i);
    }
}
