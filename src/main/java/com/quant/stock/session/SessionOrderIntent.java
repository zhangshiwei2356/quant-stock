package com.quant.stock.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话策略下单意图；由引擎撮合子集处理。volume≤0：买=按仓位工具算满手，卖=全部可卖老仓。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionOrderIntent {

    public enum Side {
        BUY,
        SELL
    }

    private Side side;
    /** 股数；≤0 表示由引擎按规则推算 */
    @Builder.Default
    private int volume = 0;
    private String reason;
    /** true 时跳过 ADV 参与率帽（预留强平等场景） */
    @Builder.Default
    private boolean bypassParticipationCap = false;

    public static SessionOrderIntent buy(int volume, String reason) {
        return SessionOrderIntent.builder().side(Side.BUY).volume(volume).reason(reason).build();
    }

    public static SessionOrderIntent sellAll(String reason) {
        return SessionOrderIntent.builder().side(Side.SELL).volume(0).reason(reason).build();
    }
}
