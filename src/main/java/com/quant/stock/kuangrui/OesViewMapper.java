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

    /** M5+：主柜资金（毫→元）。 */
    public static Map<String, Object> counterCash(String cashAcctId,
                                                  String custId,
                                                  String custName,
                                                  String bankId,
                                                  long counterAvailableBal,
                                                  long counterDrawableBal,
                                                  boolean cashTrsfDisabled) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("cashAcctId", nullToEmpty(cashAcctId));
        m.put("custId", nullToEmpty(custId));
        m.put("custName", nullToEmpty(custName));
        m.put("bankId", nullToEmpty(bankId));
        m.put("counterAvailableBal", KuangruiPriceScale.toYuanAllowZero(counterAvailableBal));
        m.put("counterDrawableBal", KuangruiPriceScale.toYuanAllowZero(counterDrawableBal));
        m.put("cashTrsfDisabled", cashTrsfDisabled);
        return m;
    }

    /** M5+：股东账户。 */
    public static Map<String, Object> invAcct(String invAcctId,
                                              String custId,
                                              int mktId,
                                              String statusLabel,
                                              boolean tradeDisabled,
                                              int pbuId,
                                              int subscriptionQuota) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("invAcctId", nullToEmpty(invAcctId));
        m.put("custId", nullToEmpty(custId));
        m.put("mktId", mktId);
        m.put("status", nullToEmpty(statusLabel));
        m.put("tradeDisabled", tradeDisabled);
        m.put("pbuId", pbuId);
        m.put("subscriptionQuota", subscriptionQuota);
        return m;
    }

    /** M5+：最大可买卖数量。 */
    public static Map<String, Object> maxTradableQty(String securityId,
                                                    String side,
                                                    long ordPriceMilli,
                                                    long minTradableQty,
                                                    long maxTradableQty) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", normalizeCode(securityId));
        m.put("side", nullToEmpty(side));
        BigDecimal px = KuangruiPriceScale.toYuan(ordPriceMilli);
        m.put("ordPrice", px == null ? BigDecimal.ZERO : px);
        m.put("minTradableQty", minTradableQty);
        m.put("maxTradableQty", maxTradableQty);
        m.put("ok", true);
        return m;
    }

    /**
     * M6：银证/出入金流水项（金额毫→元）。
     */
    public static Map<String, Object> cashTransfer(int clSeqNo,
                                                   String cashAcctId,
                                                   String direct,
                                                   String trsfType,
                                                   String trsfStatus,
                                                   long occurAmtMilli,
                                                   int counterEntrustNo,
                                                   int rejReason,
                                                   String rejReasonInfo,
                                                   String allotSerialNo,
                                                   int operDate,
                                                   int operTime) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("clSeqNo", clSeqNo);
        m.put("cashAcctId", nullToEmpty(cashAcctId));
        m.put("direct", nullToEmpty(direct));
        m.put("trsfType", nullToEmpty(trsfType));
        m.put("trsfStatus", nullToEmpty(trsfStatus));
        m.put("occurAmt", KuangruiPriceScale.toYuanAllowZero(occurAmtMilli));
        m.put("counterEntrustNo", counterEntrustNo);
        m.put("rejReason", rejReason);
        m.put("rejReasonInfo", nullToEmpty(rejReasonInfo));
        m.put("allotSerialNo", nullToEmpty(allotSerialNo));
        m.put("operDate", operDate);
        m.put("operTime", operTime);
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

    /**
     * M4 证券产品：涨跌停/昨收为元；股本为股；{@code suspended} 由柜台标志推导。
     */
    public static Map<String, Object> stock(String securityId,
                                            String securityName,
                                            long upperLimitMilli,
                                            long lowerLimitMilli,
                                            long prevCloseMilli,
                                            long outstandingShare,
                                            long publicFloatShare,
                                            int suspendFlag,
                                            int securityStatus) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", normalizeCode(securityId));
        m.put("name", nullToEmpty(securityName));
        BigDecimal up = KuangruiPriceScale.toYuan(upperLimitMilli);
        BigDecimal down = KuangruiPriceScale.toYuan(lowerLimitMilli);
        BigDecimal prev = KuangruiPriceScale.toYuan(prevCloseMilli);
        m.put("upperLimit", up);
        m.put("lowerLimit", down);
        m.put("prevClose", prev);
        m.put("outstandingShare", outstandingShare);
        m.put("publicFloatShare", publicFloatShare);
        m.put("suspendFlag", suspendFlag);
        m.put("securityStatus", securityStatus);
        m.put("suspended", isSuspendedFlag(suspendFlag, securityStatus));
        // 流通股本 → 亿股（市值过滤用）
        if (publicFloatShare > 0L) {
            m.put("floatSharesYi", BigDecimal.valueOf(publicFloatShare)
                    .divide(new BigDecimal("100000000"), 4, java.math.RoundingMode.HALF_UP));
        } else if (outstandingShare > 0L) {
            m.put("floatSharesYi", BigDecimal.valueOf(outstandingShare)
                    .divide(new BigDecimal("100000000"), 4, java.math.RoundingMode.HALF_UP));
        } else {
            m.put("floatSharesYi", null);
        }
        return m;
    }

    /** M4 交易日：YYYYMMDD int → ISO 日期字符串。 */
    public static Map<String, Object> tradingDay(int yyyymmdd) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("tradingDayRaw", yyyymmdd);
        m.put("tradingDay", formatYyyymmdd(yyyymmdd));
        return m;
    }

    /**
     * M4 佣金：柜台 feeRate 常见为 ×1e8 整数（如 30000 → 0.0003）；已是小数则原样。
     */
    public static Map<String, Object> commission(int feeType,
                                                 int bsType,
                                                 long feeRateRaw,
                                                 long minFeeMilli,
                                                 BigDecimal feeRateDecimal) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("feeType", feeType);
        m.put("bsType", bsType);
        m.put("feeRateRaw", feeRateRaw);
        BigDecimal rate = feeRateDecimal;
        if (rate == null && feeRateRaw > 0L) {
            rate = decodeFeeRate(feeRateRaw);
        }
        m.put("feeRate", rate);
        BigDecimal minFee = KuangruiPriceScale.toYuan(minFeeMilli);
        m.put("minFee", minFee == null ? BigDecimal.ZERO : minFee);
        return m;
    }

    /** 柜台费率整数 → 小数；优先按 1e8 解（OES 常见）。 */
    public static BigDecimal decodeFeeRate(long raw) {
        if (raw <= 0L) {
            return null;
        }
        // 已是「万分之」量级误传（如 3 → 0.0003）极少见；主路径 1e8
        if (raw < 1000L) {
            return BigDecimal.valueOf(raw).divide(new BigDecimal("10000"), 8, java.math.RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(raw).divide(new BigDecimal("100000000"), 8, java.math.RoundingMode.HALF_UP);
    }

    /** 停牌：suspendFlag≠0，或 securityStatus 落入常见停牌枚举值（依资料包粗判）。 */
    public static boolean isSuspendedFlag(int suspendFlag, int securityStatus) {
        if (suspendFlag != 0) {
            return true;
        }
        // 常见：0=正常；部分版本非 0 表示异常/停牌
        return securityStatus < 0;
    }

    /** YYYYMMDD → yyyy-MM-dd；非法返回空串。 */
    public static String formatYyyymmdd(int yyyymmdd) {
        if (yyyymmdd < 19700101 || yyyymmdd > 21001231) {
            return "";
        }
        int y = yyyymmdd / 10000;
        int mo = (yyyymmdd / 100) % 100;
        int d = yyyymmdd % 100;
        if (mo < 1 || mo > 12 || d < 1 || d > 31) {
            return "";
        }
        return String.format("%04d-%02d-%02d", y, mo, d);
    }

    /** 柜台状态 → 本地 {@code OrderDTO.Status} 名（未知保持 SUBMITTED）。 */
    public static String toLocalStatusName(int oesStatus) {
        switch (oesStatus) {
            case 3:
                return "PARTIAL";
            case 8:
                return "FILLED";
            case 5:
            case 6: // PARTIALLY_CANCELED：余量已撤，本地收束为 CANCELLED（保留已成量）
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
