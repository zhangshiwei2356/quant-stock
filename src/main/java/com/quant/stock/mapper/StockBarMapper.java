package com.quant.stock.mapper;

import com.quant.stock.market.dto.StockBarDO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分表K线 Mapper：通过 tableName 路由到 stock_bar_*
 * 仅在 quant.db-enabled=true 时启用
 */
public interface StockBarMapper {

    /** 按标的与时间区间查询分表 K 线，按 bar_time 升序。 */
    List<StockBarDO> selectRange(@Param("tableName") String tableName,
                                 @Param("stockCode") String stockCode,
                                 @Param("start") LocalDateTime start,
                                 @Param("end") LocalDateTime end);

    /** 统计指定标的在时间区间内的 K 线条数。 */
    int countRange(@Param("tableName") String tableName,
                   @Param("stockCode") String stockCode,
                   @Param("start") LocalDateTime start,
                   @Param("end") LocalDateTime end);

    /** 查询该标的在分表中的最新 bar_time。 */
    LocalDateTime selectMaxBarTime(@Param("tableName") String tableName,
                                   @Param("stockCode") String stockCode);

    /** 批量插入 K 线，主键冲突时更新 OHLCV 与成交额。 */
    int batchUpsert(@Param("tableName") String tableName,
                    @Param("list") List<StockBarDO> list);

    /** 删除指定标的在时间区间内的 K 线记录。 */
    int deleteRange(@Param("tableName") String tableName,
                    @Param("stockCode") String stockCode,
                    @Param("start") LocalDateTime start,
                    @Param("end") LocalDateTime end);
}
