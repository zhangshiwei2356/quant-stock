package com.quant.stock.session;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.trade.CapacityThrottle;
import com.quant.stock.trade.ParticipationCap;

import java.math.BigDecimal;
import java.util.List;

/**
 * 会话撮合参与率：ADV 硬顶经 AUM 收紧后再做当根 POV（与经典组合 P0-112 对齐）。
 */
public final class SessionParticipation {

    private SessionParticipation() {
    }

    /**
     * @param bypass 为 true 时不裁剪（止损/熔断等）
     * @param equity 组合或单股权益，供 AUM 缩放；可 null
     */
    public static int capVolume(int volume, List<BarDTO> bars, int index,
                                QuantProperties props, BigDecimal equity, boolean bypass) {
        int vol = (volume / 100) * 100;
        if (bypass || vol < 100 || props == null) {
            return vol;
        }
        long adv = avgVol(bars, index, 20);
        BigDecimal eff = CapacityThrottle.effectiveMaxParticipation(
                props.getMaxParticipationAdv(), equity, props.getCapacityAumBase());
        vol = ParticipationCap.capVolume(vol, adv, eff);
        long barVol = 0L;
        if (bars != null && index >= 0 && index < bars.size() && bars.get(index) != null
                && bars.get(index).getVolume() != null) {
            barVol = bars.get(index).getVolume().longValue();
        }
        return CapacityThrottle.povCapVolume(vol, barVol, props.getPovMaxBarVolumePct());
    }

    static long avgVol(List<BarDTO> bars, int index, int n) {
        if (bars == null || index < 0) {
            return 0L;
        }
        int from = Math.max(0, index - n + 1);
        long sum = 0;
        int cnt = 0;
        for (int i = from; i <= index && i < bars.size(); i++) {
            if (bars.get(i) != null && bars.get(i).getVolume() != null) {
                sum += bars.get(i).getVolume().longValue();
                cnt++;
            }
        }
        return cnt == 0 ? 0L : sum / cnt;
    }
}
