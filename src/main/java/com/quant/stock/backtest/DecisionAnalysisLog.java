package com.quant.stock.backtest;

import com.quant.stock.backtest.dto.AnalysisEvent;
import com.quant.stock.backtest.dto.BackTradeRecord;
import com.quant.stock.strategy.IndicatorSignalUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回测过程决策分析收集器：记录为何信号、看了哪些数据、买多少股的依据。
 */
public class DecisionAnalysisLog {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_EVENTS = 3000;

    private final List<AnalysisEvent> events = new ArrayList<AnalysisEvent>();
    private int signalBuy;
    private int signalSell;
    private int signalPyramid;
    private int fillBuy;
    private int fillSell;
    private int reject;
    private int stop;
    private int risk;
    private int expire;
    private boolean truncated;

    public void signalBuy(String code, LocalDateTime t, String reason, Map<String, Object> data) {
        signalBuy++;
        add("SIGNAL_BUY", code, t, "金叉挂买单", reason, data);
    }

    public void signalPyramid(String code, LocalDateTime t, String reason, Map<String, Object> data) {
        signalPyramid++;
        add("SIGNAL_PYRAMID", code, t, "金字塔加仓挂单", reason, data);
    }

    public void signalSell(String code, LocalDateTime t, String reason, Map<String, Object> data) {
        signalSell++;
        add("SIGNAL_SELL", code, t, "卖出信号挂单", reason, data);
    }

    public void fillBuy(String code, LocalDateTime t, String reason, Map<String, Object> data) {
        fillBuy++;
        add("FILL_BUY", code, t, "买入成交", reason, data);
    }

    public void fillSell(String code, LocalDateTime t, String reason, Map<String, Object> data) {
        fillSell++;
        add("FILL_SELL", code, t, "卖出成交", reason, data);
    }

    public void stop(String code, LocalDateTime t, String reason, Map<String, Object> data) {
        stop++;
        add("STOP", code, t, "止损/移动止盈", reason, data);
    }

    public void reject(String code, LocalDateTime t, String title, String reason, Map<String, Object> data) {
        reject++;
        add("REJECT", code, t, title == null ? "未成交/拒绝" : title, reason, data);
    }

    public void expire(String code, LocalDateTime t, String reason, Map<String, Object> data) {
        expire++;
        add("EXPIRE", code, t, "挂单过期", reason, data);
    }

    public void risk(String code, LocalDateTime t, String reason, Map<String, Object> data) {
        risk++;
        add("RISK", code, t, "账户风控", reason, data);
    }

    public List<AnalysisEvent> events() {
        return events;
    }

    public String summary() {
        String base = String.format(
                "信号买%d/卖%d/加仓%d · 成交买%d/卖%d · 止损%d · 拒绝%d · 过期%d · 风控%d",
                signalBuy, signalSell, signalPyramid, fillBuy, fillSell, stop, reject, expire, risk);
        return truncated ? base + "（事件已截断至" + MAX_EVENTS + "条）" : base;
    }

    /** 由成交流水反推分析（组合引擎补充用） */
    public static DecisionAnalysisLog fromTrades(List<BackTradeRecord> trades) {
        DecisionAnalysisLog log = new DecisionAnalysisLog();
        if (trades == null) {
            return log;
        }
        for (BackTradeRecord t : trades) {
            if (t == null) {
                continue;
            }
            Map<String, Object> d = new LinkedHashMap<String, Object>();
            d.put("成交价", t.getPrice());
            d.put("股数", t.getVolume());
            d.put("成交额", t.getAmount());
            d.put("费用", t.getFee());
            d.put("说明", "组合引擎成交记录；详细信号依据见单股回测分析");
            String side = t.getSide() == null ? "" : t.getSide().trim().toUpperCase();
            if ("BUY".equals(side)) {
                log.fillBuy(t.getStockCode(), t.getTradeTime(),
                        "组合买入成交（共享资金池、次日开盘撮合、成本模型）", d);
            } else if ("SELL".equals(side)) {
                log.fillSell(t.getStockCode(), t.getTradeTime(),
                        "组合卖出成交（死叉/止损/熔断等触发后的撮合）", d);
            }
        }
        return log;
    }

    public static Map<String, Object> indSnapshot(IndicatorSignalUtil.IndicatorBundle ind, int i,
                                                  BigDecimal close, BigDecimal cash, BigDecimal equity,
                                                  BigDecimal posScale, BigDecimal atr) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (ind != null && i >= 0) {
            m.put("MA5", round(ind.ma5[i]));
            m.put("MA20", round(ind.ma20[i]));
            if (!Double.isNaN(ind.ma60[i])) {
                m.put("MA60", round(ind.ma60[i]));
            }
            if (!Double.isNaN(ind.rsi14[i])) {
                m.put("RSI14", round(ind.rsi14[i]));
            }
            if (!Double.isNaN(ind.atr14[i])) {
                m.put("ATR14", round(ind.atr14[i]));
            }
            if (!Double.isNaN(ind.adx14[i])) {
                m.put("ADX14", round(ind.adx14[i]));
            }
            boolean crossUp = ind.isMaCrossUp(i);
            boolean crossDown = ind.isMaCrossDown(i);
            m.put("金叉", crossUp);
            m.put("死叉", crossDown);
        }
        if (close != null) {
            m.put("收盘价", close);
        }
        if (cash != null) {
            m.put("可用资金", cash.setScale(2, RoundingMode.HALF_UP));
        }
        if (equity != null) {
            m.put("权益", equity.setScale(2, RoundingMode.HALF_UP));
        }
        if (posScale != null) {
            m.put("仓位系数", posScale);
        }
        if (atr != null) {
            m.put("ATR", atr);
        }
        return m;
    }

    public static Object round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return null;
        }
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    private void add(String type, String code, LocalDateTime t, String title, String reason, Map<String, Object> data) {
        if (events.size() >= MAX_EVENTS) {
            truncated = true;
            return;
        }
        events.add(AnalysisEvent.builder()
                .type(type)
                .stockCode(code)
                .time(t == null ? null : t.format(FMT))
                .title(title)
                .reason(reason)
                .data(data == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(data))
                .build());
    }
}
