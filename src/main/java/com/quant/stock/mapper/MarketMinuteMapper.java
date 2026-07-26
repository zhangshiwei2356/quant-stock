package com.quant.stock.mapper;

import com.quant.stock.market.dto.MarketMinuteDO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 原始分钟行情 Mapper：market_minute 表的查询与批量 upsert。
 */
public interface MarketMinuteMapper {

    /** 统计某 symbol 在 market_minute 中的总行数。 */
    int countBySymbol(@Param("symbol") String symbol);

    /** 按 symbol 与时间区间查询分钟线，按时间升序。 */
    List<MarketMinuteDO> selectRange(@Param("symbol") String symbol,
                                     @Param("start") LocalDateTime start,
                                     @Param("end") LocalDateTime end);

    /** 批量写入分钟线，冲突时更新 OHLCV 等字段。 */
    int batchUpsert(@Param("list") List<MarketMinuteDO> list);
}
