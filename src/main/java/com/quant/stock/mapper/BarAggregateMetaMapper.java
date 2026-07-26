package com.quant.stock.mapper;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * K 线聚合元数据 Mapper：记录各周期已聚合进度与源表最大时间。
 */
public interface BarAggregateMetaMapper {

    /** 读取 bar_aggregate_meta 中该标的、周期的 source_max_time。 */
    LocalDateTime selectSourceMaxTime(@Param("stockCode") String stockCode,
                                      @Param("period") String period);

    /** 插入或更新聚合元数据（last_agg_time、source_max_time）。 */
    int upsertMeta(@Param("stockCode") String stockCode,
                   @Param("period") String period,
                   @Param("lastAggTime") LocalDateTime lastAggTime,
                   @Param("sourceMaxTime") LocalDateTime sourceMaxTime);
}
