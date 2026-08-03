package com.quant.stock.market;

/**
 * {@code market_1min.data_source} 取值约定（库内价额一律为「元」；宽睿毫级仅在适配层换算）。
 */
public final class MarketDataSources {

    /** classpath 演示种子 */
    public static final String MOCK = "MOCK";
    /** 通达信公开节点回填 */
    public static final String TDX = "TDX";
    /** 宽睿 MDS（M1 及以后） */
    public static final String MDS = "MDS";

    private MarketDataSources() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return TDX;
        }
        return raw.trim().toUpperCase();
    }
}
