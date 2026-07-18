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

    List<StockBarDO> selectRange(@Param("tableName") String tableName,
                                 @Param("stockCode") String stockCode,
                                 @Param("start") LocalDateTime start,
                                 @Param("end") LocalDateTime end);

    int countRange(@Param("tableName") String tableName,
                   @Param("stockCode") String stockCode,
                   @Param("start") LocalDateTime start,
                   @Param("end") LocalDateTime end);

    LocalDateTime selectMaxBarTime(@Param("tableName") String tableName,
                                   @Param("stockCode") String stockCode);

    int batchUpsert(@Param("tableName") String tableName,
                    @Param("list") List<StockBarDO> list);

    int deleteRange(@Param("tableName") String tableName,
                    @Param("stockCode") String stockCode,
                    @Param("start") LocalDateTime start,
                    @Param("end") LocalDateTime end);
}
