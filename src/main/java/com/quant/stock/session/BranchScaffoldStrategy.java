package com.quant.stock.session;

import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.strategy.BaseStrategy;
import com.quant.stock.strategy.IndicatorSignalUtil;
import com.quant.stock.strategy.dto.TradeSignal;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 会话三分支脚手架：注册 id={@code branchScaffold}；只记事件、不发真实买卖单。
 * 不实现隔日高开公式；经典引擎调用本类买卖信号恒为 false。
 */
@Component
public class BranchScaffoldStrategy extends BaseStrategy implements SessionStrategy {

    public static final String ID = "branchScaffold";

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String name() {
        return ID;
    }

    @Override
    public String uiLabel() {
        return "分支脚手架（session）";
    }

    @Override
    public String profileSummary() {
        return "MIN_1 三分支旁路验收；无真实撮合；缺 INDEX/竞价/封单时分支 UNAVAILABLE";
    }

    @Override
    public String detailIntro() {
        return "会话三分支脚手架（branchScaffold）。在 1 分钟 K 上旁路记录分支事件，不发真实买卖单；"
                + "经典引擎买卖信号恒为 false。用于验收 session 分支与 fill-mode，缺 INDEX/竞价/封单时标记 UNAVAILABLE。"
                + "评分仅反映旁路回测摘要，不代表可交易策略绩效。";
    }

    @Override
    public String sessionId() {
        return ID;
    }

    @Override
    public Set<DataDep> dataDeps() {
        return EnumSet.of(DataDep.MIN1);
    }

    @Override
    public int maxHoldTradingDays() {
        return 2;
    }

    @Override
    public void onSessionOpen(SessionContext ctx, List<SessionEvent> out) {
        if (ctx == null || out == null) {
            return;
        }
        String time = timeOf(ctx);
        HoldDayState state = ctx.getHoldState() == null ? HoldDayState.FLAT : ctx.getHoldState();
        if (state == HoldDayState.FLAT) {
            ctx.setHoldState(HoldDayState.HOLD_D0);
            out.add(ev(time, "ENTER_HOLD", ctx.getBranch(), "演示：FLAT→HOLD_D0"));
            return;
        }
        int nextIdx = state.holdDayIndex() + 1;
        if (nextIdx >= maxHoldTradingDays()) {
            ctx.setHoldState(HoldDayState.FLAT);
            out.add(ev(time, "FORCE_FLAT", ctx.getBranch(),
                    "演示：持有达 maxHoldTradingDays=" + maxHoldTradingDays() + " → FLAT"));
        } else {
            HoldDayState next = state.nextHold();
            ctx.setHoldState(next);
            out.add(ev(time, "HOLD_ADVANCE", ctx.getBranch(),
                    "演示：" + state + "→" + next));
        }
    }

    @Override
    public void onBranchBar(SessionContext ctx, List<SessionEvent> out) {
        if (ctx == null || out == null || ctx.getBranch() == null) {
            return;
        }
        out.add(ev(timeOf(ctx), "BRANCH_TICK", ctx.getBranch(),
                "进入分支 " + ctx.getBranch() + " hold=" + ctx.getHoldState()));
    }

    @Override
    public void onSessionClose(SessionContext ctx, List<SessionEvent> out) {
        if (ctx == null || out == null) {
            return;
        }
        out.add(ev(timeOf(ctx), "SESSION_CLOSE", ctx.getBranch(),
                "日终 hold=" + ctx.getHoldState()));
    }

    @Override
    public TradeSignal calcSignal(String stockCode, List<BarDTO> closedBars) {
        return TradeSignal.none(stockCode);
    }

    @Override
    public boolean isBuySignalAt(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        return false;
    }

    @Override
    public boolean isSellSignalAt(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        return false;
    }

    private static String timeOf(SessionContext ctx) {
        if (ctx.getBar() != null && ctx.getBar().getBarBegin() != null) {
            return FMT.format(ctx.getBar().getBarBegin());
        }
        return ctx.getSessionDay() == null ? "" : ctx.getSessionDay().toString();
    }

    private static SessionEvent ev(String time, String type, SessionBranch branch, String detail) {
        return SessionEvent.builder()
                .time(time)
                .type(type)
                .branch(branch == null ? null : branch.name())
                .detail(detail)
                .build();
    }
}
