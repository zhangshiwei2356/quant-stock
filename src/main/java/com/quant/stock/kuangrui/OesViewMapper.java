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
     * OES 委托状态粗映射（对齐 eOesOrdStatusT）。
     */
    public static String ordStatusLabel(int status) {
        switch (status) {
            case 0:
                return "PENDING";
            case 1:
                return "NEW";
            case 2:
                return "DECLARED";
            case 3:
                return "PARTIAL";
            case 5:
                return "CANCEL_DONE";
            case 6:
                return "PARTIAL_CANCELLED";
            case 7:
                return "CANCELLED";
            case 8:
                return "FILLED";
            default:
                if (status >= 10) {
                    return "REJECTED";
                }
                return "STATUS_" + status;
        }
    }

    /** 柜台状态 → 本地 {@code OrderDTO.Status} 名（未知保持 SUBMITTED）。 */
    public static String toLocalStatusName(int oesStatus) {
        switch (oesStatus) {
            case 3:
            case 6:
                return "PARTIAL";
            case 8:
                return "FILLED";
            case 5:
            case 7:
                return "CANCELLED";
            default:
                if (oesStatus >= 10) {
                    return "REJECTED";
                }
                return "SUBMITTED";
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
