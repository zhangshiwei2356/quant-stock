package com.quant.stock.trade;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 真实交易成本：佣金 + 印花税(卖) + 分级滑点 + 冲击成本
 */
@Component
@RequiredArgsConstructor
public class TradeCostModel {

    private final QuantProperties props;

    public BigDecimal slipRate(List<BarDTO> bars, int index) {
        long avgVol = avgVolume(bars, index, 20);
        if (avgVol >= props.getVolLargeThreshold()) {
            return props.getSlipLarge();
        }
        if (avgVol >= props.getVolMidThreshold()) {
            return props.getSlipMid();
        }
        return props.getSlipSmall();
    }

    public BigDecimal buyPrice(BigDecimal base, List<BarDTO> bars, int index, int volume) {
        BigDecimal slip = slipRate(bars, index);
        BigDecimal impact = impactRate(bars, index, volume);
        return base.multiply(BigDecimal.ONE.add(slip).add(impact)).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal sellPrice(BigDecimal base, List<BarDTO> bars, int index, int volume) {
        BigDecimal slip = slipRate(bars, index);
        BigDecimal impact = impactRate(bars, index, volume);
        return base.multiply(BigDecimal.ONE.subtract(slip).subtract(impact)).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal buyFee(BigDecimal amount) {
        return buyFee(amount, props.getFeeRate());
    }

    public BigDecimal buyFee(BigDecimal amount, BigDecimal feeRate) {
        BigDecimal rate = feeRate == null ? props.getFeeRate() : feeRate;
        return commissionWithFloor(amount, rate).setScale(2, RoundingMode.HALF_UP);
    }

    /** 卖出：佣金（含最低 5 元）+ 印花税 */
    public BigDecimal sellFee(BigDecimal amount) {
        return sellFee(amount, props.getFeeRate());
    }

    public BigDecimal sellFee(BigDecimal amount, BigDecimal feeRate) {
        BigDecimal rate = feeRate == null ? props.getFeeRate() : feeRate;
        BigDecimal commission = commissionWithFloor(amount, rate);
        BigDecimal stamp = amount.multiply(props.getStampTaxRate());
        return commission.add(stamp).setScale(2, RoundingMode.HALF_UP);
    }

    /** A 股常见最低佣金 5 元 */
    private BigDecimal commissionWithFloor(BigDecimal amount, BigDecimal rate) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal commission = amount.multiply(rate == null ? BigDecimal.ZERO : rate);
        BigDecimal floor = new BigDecimal("5");
        return commission.compareTo(floor) < 0 ? floor : commission;
    }

    private BigDecimal impactRate(List<BarDTO> bars, int index, int volume) {
        long avgVol = Math.max(1L, avgVolume(bars, index, 20));
        double ratio = (double) volume / (double) avgVol;
        return props.getImpactCoeff().multiply(BigDecimal.valueOf(ratio))
                .min(new BigDecimal("0.02"));
    }

    private long avgVolume(List<BarDTO> bars, int index, int n) {
        if (bars == null || index < 0) {
            return 0L;
        }
        int from = Math.max(0, index - n + 1);
        long sum = 0;
        int cnt = 0;
        for (int i = from; i <= index && i < bars.size(); i++) {
            if (bars.get(i).getVolume() != null) {
                sum += bars.get(i).getVolume().longValue();
                cnt++;
            }
        }
        return cnt == 0 ? 0L : sum / cnt;
    }
}
