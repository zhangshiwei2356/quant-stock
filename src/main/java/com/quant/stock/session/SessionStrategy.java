package com.quant.stock.session;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 会话/多分支策略契约（旁路引擎用；不替代 {@link com.quant.stock.strategy.BaseStrategy} 金叉路径）。
 */
public interface SessionStrategy {

    String sessionId();

    /** 默认仅需 1 分钟行情。 */
    default Set<DataDep> dataDeps() {
        return EnumSet.of(DataDep.MIN1);
    }

    /** 隔日高开形状示意：最长持有交易日（脚手架演示用）。 */
    default int maxHoldTradingDays() {
        return 2;
    }

    /** 哪些分支依赖尚未满足的 deps（引擎据此降级）。默认：非 MIN1 的 dep 影响全部分支。 */
    default Set<SessionBranch> branchesAffectedBy(DataDep missing) {
        if (missing == null || missing == DataDep.MIN1) {
            return EnumSet.noneOf(SessionBranch.class);
        }
        return EnumSet.allOf(SessionBranch.class);
    }

    /**
     * 是否每根分钟回调 {@link #onBranchBar}（默认 false：仅分支日首根，避免事件爆炸）。
     */
    default boolean tickEveryBar() {
        return false;
    }

    void onSessionOpen(SessionContext ctx, List<SessionEvent> out);

    void onBranchBar(SessionContext ctx, List<SessionEvent> out);

    void onSessionClose(SessionContext ctx, List<SessionEvent> out);

    /**
     * 钩子后拉取下单意图；默认空（脚手架不下单）。引擎在 matchingEnabled 时撮合。
     */
    default List<SessionOrderIntent> pollIntents(SessionContext ctx) {
        return Collections.emptyList();
    }
}
