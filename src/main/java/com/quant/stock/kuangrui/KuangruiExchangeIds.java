package com.quant.stock.kuangrui;


import lombok.extern.slf4j.Slf4j;
/**
 * A 股代码 → 宽睿交易所枚举数值（与资料包 {@code MdsExchangeId} 一致）：
 * 1=上交所 / 2=深交所 / 3=北交所。
 */
@Slf4j
public final class KuangruiExchangeIds {

    public static final int SSE = 1;
    public static final int SZSE = 2;
    public static final int BSE = 3;

    private KuangruiExchangeIds() {
    }

    /**
     * @param code 6 位数字代码；非法返回 0
     */
    public static int fromStockCode(String code) {
        if (code == null) {
            return 0;
        }
        String c = code.trim();
        if (c.length() < 1) {
            return 0;
        }
        char first = c.charAt(0);
        if (first == '6') {
            return SSE;
        }
        if (first == '0' || first == '3') {
            return SZSE;
        }
        if (first == '4' || first == '8' || first == '9') {
            return BSE;
        }
        return 0;
    }

    /** 纯数字 instrId；非法返回 0。 */
    public static int toInstrId(String code) {
        if (code == null) {
            return 0;
        }
        String c = code.trim();
        if (c.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(c);
        } catch (NumberFormatException e) {
            log.error("宽睿交易所 ID 解析异常", e);
            return 0;
        }
    }
}
