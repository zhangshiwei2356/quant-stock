package com.quant.stock.market;

import com.quant.stock.market.BarAggregateUtil.Period;

/**
 * K线周期枚举：查询层对外统一使用，内部映射到聚合逻辑。
 * <p>
 * 主路径只读 {@code market_1min} 并内存聚合；{@code tableName} 仅为历史/JSON 元数据字段，不再对应分表读写。
 */
public enum BarPeriod {
    MIN_1("stock_bar_1min", null),
    MIN_5("stock_bar_5min", Period.M5),
    MIN_15("stock_bar_15min", Period.M15),
    MIN_30("stock_bar_30min", Period.M30),
    MIN_60("stock_bar_60min", Period.M60),
    DAY("stock_bar_day", Period.DAY),
    WEEK("stock_bar_week", Period.WEEK),
    MONTH("stock_bar_month", Period.MONTH);

    /** @deprecated 旧分表名残留；仅 API/JSON 元数据展示，无存储路由 */
    @Deprecated
    private final String tableName;
    private final Period aggregatePeriod;

    BarPeriod(String tableName, Period aggregatePeriod) {
        this.tableName = tableName;
        this.aggregatePeriod = aggregatePeriod;
    }

    /**
     * 历史表名标签（如 {@code stock_bar_5min}）。
     *
     * @deprecated 分表路径已删除；勿再用于 Mapper 路由
     */
    @Deprecated
    public String getTableName() {
        return tableName;
    }

    /** 1分钟返回 null，表示无需从1min再聚合 */
    public Period getAggregatePeriod() {
        return aggregatePeriod;
    }

    /** 是否为原始层（1 分钟），无需从更细粒度再聚合 */
    public boolean isRaw() {
        return this == MIN_1;
    }

    /** 预聚合层周期（不含1min） */
    public static BarPeriod[] aggregatePeriods() {
        return new BarPeriod[]{MIN_5, MIN_15, MIN_30, MIN_60, DAY, WEEK, MONTH};
    }
}
