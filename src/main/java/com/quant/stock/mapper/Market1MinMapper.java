package com.quant.stock.mapper;

import com.quant.stock.market.dto.Market1MinDO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 原始 1 分钟行情 Mapper：market_1min 表的查询与批量 upsert。
 */
public interface Market1MinMapper {

    /** 统计某 symbol 在 market_1min 中的总行数。 */
    int countBySymbol(@Param("symbol") String symbol);

    /** 按 symbol 与时间区间查询 1 分钟线，按时间升序。 */
    List<Market1MinDO> selectRange(@Param("symbol") String symbol,
                                   @Param("start") LocalDateTime start,
                                   @Param("end") LocalDateTime end);

    /** 批量写入 1 分钟线，冲突时更新 OHLCV 等字段。 */
    int batchUpsert(@Param("list") List<Market1MinDO> list);
}
