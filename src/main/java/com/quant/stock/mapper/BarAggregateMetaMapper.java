package com.quant.stock.mapper;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface BarAggregateMetaMapper {

    LocalDateTime selectSourceMaxTime(@Param("stockCode") String stockCode,
                                      @Param("period") String period);

    int upsertMeta(@Param("stockCode") String stockCode,
                   @Param("period") String period,
                   @Param("lastAggTime") LocalDateTime lastAggTime,
                   @Param("sourceMaxTime") LocalDateTime sourceMaxTime);
}
