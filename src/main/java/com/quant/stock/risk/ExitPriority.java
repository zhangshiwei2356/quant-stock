package com.quant.stock.risk;

/**
 * 退出规则优先级契约（P0-127）。
 * <p>
 * 引擎日内顺序：①撮合昨日挂单 → ②止损/trail（当根）→ ③账户熔断挂卖 → ④死叉挂卖。
 * 数值越大优先级越高；同日已止损清仓则不再挂死叉。
 */
public enum ExitPriority {

    /** 当根立即执行；高于一切挂单卖出 */
    STOP_LOSS(100, "止损/移动止盈"),
    /** 账户回撤熔断：挂清仓且禁新开；可抢占尚未成交的死叉挂单意图（先到先挂） */
    ACCOUNT_HALT(80, "回撤熔断"),
    /** 最大持仓交易日到期（P0-114）；挂清仓，不受 ADV 参与率限制 */
    TIME_STOP(70, "时间止损"),
    /** 信号卖出：若当日已止损清仓或已有挂卖则忽略 */
    DEATH_CROSS(40, "死叉");

    private final int rank;
    private final String label;

    ExitPriority(int rank, String label) {
        this.rank = rank;
        this.label = label;
    }

    public int getRank() {
        return rank;
    }

    public String getLabel() {
        return label;
    }

    /** 是否允许新注册该退出挂单（止损为当根执行，不走挂单注册）。 */
    public boolean canRegisterPending(boolean stoppedOutToday, boolean pendingSellAlready) {
        if (this == STOP_LOSS) {
            return true;
        }
        if (this == ACCOUNT_HALT || this == TIME_STOP) {
            return !pendingSellAlready;
        }
        // DEATH_CROSS
        return !stoppedOutToday && !pendingSellAlready;
    }

    /** 风控类退出：参与率硬顶不限制（避免无法减险）。 */
    public boolean bypassParticipationCap() {
        return this == STOP_LOSS || this == ACCOUNT_HALT || this == TIME_STOP;
    }

    public static ExitPriority fromReasonLabel(String reason) {
        if (reason == null || reason.isEmpty()) {
            return null;
        }
        for (ExitPriority p : values()) {
            if (reason.startsWith(p.label)) {
                return p;
            }
        }
        return null;
    }
}
