package com.quant.stock.mapper;

import com.quant.stock.market.dto.MarketMinuteDO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MarketMinuteMapper {

    int countBySymbol(@Param("symbol") String symbol);

    List<MarketMinuteDO> selectRange(@Param("symbol") String symbol,
                                     @Param("start") LocalDateTime start,
                                     @Param("end") LocalDateTime end);

    int batchUpsert(@Param("list") List<MarketMinuteDO> list);
}
