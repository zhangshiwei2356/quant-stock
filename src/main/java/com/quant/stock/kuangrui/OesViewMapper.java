package com.quant.stock.kuangrui;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OES 查询结果 → 业务视图（价÷10000，代码补齐 6 位）。不依赖 quant360 jar。
 */
public final class OesViewMapper {

    private OesViewMapper() {
    }

    /** 资金：毫级余额 → 元。 */
    public static Map<String, Object> cash(String cashAcctId,
                                           long currentTotalBal,
                                           long currentAvailableBal,
                                           long currentDrawableBal) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("cashAcctId", nullToEmpty(cashAcctId));
        m.put("currentTotalBal", KuangruiPriceScale.toYuanAllowZero(currentTotalBal));
        m.put("currentAvailableBal", KuangruiPriceScale.toYuanAllowZero(currentAvailableBal));
        m.put("currentDrawableBal", KuangruiPriceScale.toYuanAllowZero(currentDrawableBal));
        return m;
    }

    /** 持仓：数量为股；成本价毫→元。 */
    public static Map<String, Object> holding(String securityId,
                                              long sumHld,
                                              long sellAvlHld,
                                              long costPriceMilli) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", normalizeCode(securityId));
        m.put("sumHld", sumHld);
        m.put("sellAvlHld", sellAvlHld);
        BigDecimal cost = KuangruiPriceScale.toYuan(costPriceMilli);
        m.put("costPrice", cost == null ? BigDecimal.ZERO : cost);
        return m;
    }

    /** 委托：价额毫→元。 */
    public static Map<String, Object> order(String securityId,
                                            long clOrdId,
                                            int clSeqNo,
                                            int ordStatus,
                                            long ordPriceMilli,
                                            int ordQty,
                                            int cumQty) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", normalizeCode(securityId));
        m.put("clOrdId", clOrdId);
        m.put("clSeqNo", clSeqNo);
        m.put("ordStatus", ordStatus);
        m.put("ordStatusLabel", ordStatusLabel(ordStatus));
        BigDecimal px = KuangruiPriceScale.toYuan(ordPriceMilli);
        m.put("ordPrice", px == null ? BigDecimal.ZERO : px);
        m.put("ordQty", ordQty);
        m.put("cumQty", cumQty);
        return m;
    }

    /** 成交：价额毫→元。 */
    public static Map<String, Object> trade(String securityId,
                                            long clOrdId,
                                            long trdPriceMilli,
                                            int trdQty,
                                            long trdAmtMilli) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", normalizeCode(securityId));
        m.put("clOrdId", clOrdId);
        BigDecimal px = KuangruiPriceScale.toYuan(trdPriceMilli);
        m.put("trdPrice", px == null ? BigDecimal.ZERO : px);
        m.put("trdQty", trdQty);
        m.put("trdAmt", KuangruiPriceScale.toYuanAllowZero(trdAmtMilli));
        return m;
    }

    /** 6 位数字代码；非法原样 trim。 */
    public static String normalizeCode(String securityId) {
        if (securityId == null) {
            return "";
        }
        String c = securityId.trim();
        if (c.isEmpty()) {
            return "";
        }
        // 去掉可能的市场后缀
        int dot = c.indexOf('.');
        if (dot > 0) {
            c = c.substring(0, dot);
        }
        String digits = c.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return c;
        }
        while (digits.length() < 6) {
            digits = "0" + digits;
        }
        if (digits.length() > 6) {
            digits = digits.substring(digits.length() - 6);
        }
        return digits;
    }

    /**
     * OES 委托状态粗映射（现货常用；未知保留数字）。
     * 对齐资料包常见枚举：申报中/已报/部成/已成/部撤/已撤/废单等。
     */
    public static String ordStatusLabel(int status) {
        switch (status) {
            case 0:
                return "PENDING_NEW";
            case 1:
                return "NEW";
            case 2:
                return "PARTIAL";
            case 3:
                return "FILLED";
            case 4:
                return "CANCELLED";
            case 5:
                return "PARTIAL_CANCELLED";
            case 6:
                return "PARTIAL_FILLED_CANCELLED";
            case 8:
                return "REJECTED";
            default:
                return "STATUS_" + status;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
