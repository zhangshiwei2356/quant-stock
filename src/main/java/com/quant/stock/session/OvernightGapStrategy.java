package com.quant.stock.session;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.strategy.BaseStrategy;
import com.quant.stock.strategy.IndicatorSignalUtil;
import com.quant.stock.strategy.dto.TradeSignal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 全时段隔日高开三分支策略：注册 id={@code overnightGap}。
 * <p>
 * 形状：尾盘布局隔夜 → 次日早盘按高开兑现/低开止损/隔日退出 → 盘中回撤风控；最长持有 2 个交易日。
 * 仅依赖 MIN_1；经典引擎买卖信号恒为 false（须走 session 旁路）。
 */
@Component
@RequiredArgsConstructor
public class OvernightGapStrategy extends BaseStrategy implements SessionStrategy {

    public static final String ID = "overnightGap";

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final QuantProperties props;

    @Override
    public String name() {
        return ID;
    }

    @Override
    public String uiLabel() {
        return "隔日高开三分支（overnightGap）";
    }

    @Override
    public String profileSummary() {
        return "MIN_1 三分支：尾盘布局、早盘兑现/止损、盘中回撤；最长持有 2 日";
    }

    @Override
    public String detailIntro() {
        return "全时段隔日高开三分支（overnightGap）。在 1 分钟 K 旁路会话引擎上："
                + "CLOSE 尾盘在温和日收益且收盘站上开盘时布局隔夜；"
                + "OPEN 次日按高开兑现/低开止损，否则隔日退出；"
                + "MID 相对当日开盘回撤止损；持有达 2 个交易日强制平仓。"
                + "不并入金叉五步；阈值见 quant.session.overnight-gap.*。";
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
        if (ctx.getPositionShares() <= 0) {
            ctx.setHoldState(HoldDayState.FLAT);
            return;
        }
        HoldDayState state = ctx.getHoldState() == null ? HoldDayState.FLAT : ctx.getHoldState();
        if (state == HoldDayState.FLAT) {
            ctx.setHoldState(HoldDayState.HOLD_D0);
            out.add(ev(time, "ENTER_HOLD", ctx.getBranch(), "隔夜持仓→HOLD_D0"));
            return;
        }
        int nextIdx = state.holdDayIndex() + 1;
        if (nextIdx >= maxHoldTradingDays()) {
            out.add(ev(time, "FORCE_FLAT", ctx.getBranch(),
                    "持有达 maxHoldTradingDays=" + maxHoldTradingDays() + "，早盘将强制退出"));
            return;
        }
        HoldDayState next = state.nextHold();
        ctx.setHoldState(next);
        out.add(ev(time, "HOLD_ADVANCE", ctx.getBranch(), state + "→" + next));
    }

    @Override
    public void onBranchBar(SessionContext ctx, List<SessionEvent> out) {
        if (ctx == null || out == null || ctx.getBranch() == null) {
            return;
        }
        String gap = ctx.getGapPct() == null ? "-" : ctx.getGapPct().toPlainString();
        out.add(ev(timeOf(ctx), "BRANCH_TICK", ctx.getBranch(),
                "branch=" + ctx.getBranch() + " hold=" + ctx.getHoldState()
                        + " gap=" + gap + " pos=" + ctx.getPositionShares()));
    }

    @Override
    public void onSessionClose(SessionContext ctx, List<SessionEvent> out) {
        if (ctx == null || out == null) {
            return;
        }
        out.add(ev(timeOf(ctx), "SESSION_CLOSE", ctx.getBranch(),
                "日终 hold=" + ctx.getHoldState() + " pos=" + ctx.getPositionShares()));
    }

    @Override
    public List<SessionOrderIntent> pollIntents(SessionContext ctx) {
        if (ctx == null || ctx.isBranchDegraded() || !ctx.isMatchingEnabled()) {
            return Collections.emptyList();
        }
        SessionBranch branch = ctx.getBranch();
        if (branch == null) {
            return Collections.emptyList();
        }
        QuantProperties.OvernightGap cfg = overnightCfg();

        if (ctx.getPositionShares() > 0 && ctx.getSellableShares() > 0) {
            SessionOrderIntent sell = sellIntent(ctx, branch, cfg);
            if (sell != null) {
                return Collections.singletonList(sell);
            }
        }
        if (ctx.getPositionShares() <= 0 && branch == SessionBranch.CLOSE && closeBuySetup(ctx, cfg)) {
            return Collections.singletonList(SessionOrderIntent.buy(0, "CLOSE_SETUP"));
        }
        return Collections.emptyList();
    }

    private SessionOrderIntent sellIntent(SessionContext ctx, SessionBranch branch,
                                          QuantProperties.OvernightGap cfg) {
        HoldDayState hold = ctx.getHoldState() == null ? HoldDayState.FLAT : ctx.getHoldState();
        int holdIdx = hold.holdDayIndex();
        BigDecimal gap = ctx.getGapPct();

        if (branch == SessionBranch.OPEN) {
            if (gap != null && cfg.getGapTakeProfit() != null
                    && gap.compareTo(cfg.getGapTakeProfit()) >= 0) {
                return SessionOrderIntent.sellAll("GAP_TP");
            }
            if (gap != null && cfg.getGapStop() != null
                    && gap.compareTo(cfg.getGapStop()) <= 0) {
                return SessionOrderIntent.sellAll("GAP_SL");
            }
            // 次日及以后：隔日兑现（核心）
            if (holdIdx >= 1 || holdIdx + 1 >= maxHoldTradingDays()) {
                return SessionOrderIntent.sellAll("OVERNIGHT_EXIT");
            }
        }
        if (branch == SessionBranch.MID) {
            BigDecimal dayOpen = ctx.getDayOpen();
            BigDecimal close = ctx.getBar() == null ? null : ctx.getBar().getClose();
            BigDecimal midStop = cfg.getMidStopFromOpen();
            if (dayOpen != null && close != null && midStop != null && midStop.signum() > 0) {
                BigDecimal floor = dayOpen.multiply(BigDecimal.ONE.subtract(midStop));
                if (close.compareTo(floor) < 0) {
                    return SessionOrderIntent.sellAll("MID_SL");
                }
            }
        }
        if (branch == SessionBranch.CLOSE) {
            if (holdIdx + 1 >= maxHoldTradingDays() || holdIdx >= maxHoldTradingDays() - 1) {
                return SessionOrderIntent.sellAll("TIME_STOP");
            }
        }
        return null;
    }

    private boolean closeBuySetup(SessionContext ctx, QuantProperties.OvernightGap cfg) {
        BigDecimal dayRet = ctx.getDayRet();
        BigDecimal gap = ctx.getGapPct();
        BigDecimal dayOpen = ctx.getDayOpen();
        BigDecimal close = ctx.getBar() == null ? null : ctx.getBar().getClose();
        if (dayRet == null || gap == null || dayOpen == null || close == null) {
            return false;
        }
        if (cfg.getCloseBuyMinDayRet() != null && dayRet.compareTo(cfg.getCloseBuyMinDayRet()) < 0) {
            return false;
        }
        if (cfg.getCloseBuyMaxDayRet() != null && dayRet.compareTo(cfg.getCloseBuyMaxDayRet()) > 0) {
            return false;
        }
        if (cfg.getCloseBuyMaxGap() != null && gap.compareTo(cfg.getCloseBuyMaxGap()) > 0) {
            return false;
        }
        // 收盘站上开盘：尾盘未破开
        return close.compareTo(dayOpen) >= 0;
    }

    private QuantProperties.OvernightGap overnightCfg() {
        QuantProperties.Session sess = props == null || props.getSession() == null
                ? new QuantProperties.Session() : props.getSession();
        return sess.getOvernightGap() == null ? new QuantProperties.OvernightGap() : sess.getOvernightGap();
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
