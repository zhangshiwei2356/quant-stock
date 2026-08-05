package com.quant.stock.mapper;

import com.quant.stock.market.dto.MarketDailyDO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 日线行情 Mapper：{@code market_daily} 查询与批量 upsert。
 */
public interface MarketDailyMapper {

    /** 统计某 symbol 在 market_daily 中的总行数。 */
    int countBySymbol(@Param("symbol") String symbol);

    /** 按 symbol 与交易日区间查询日线，按日期升序。 */
    List<MarketDailyDO> selectRange(@Param("symbol") String symbol,
                                    @Param("start") LocalDate start,
                                    @Param("end") LocalDate end);

    /** 批量写入日线，冲突时更新 OHLCV 等字段。 */
    int batchUpsert(@Param("list") List<MarketDailyDO> list);
}
