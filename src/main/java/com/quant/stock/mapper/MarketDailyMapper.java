package com.quant.stock.mapper;

import com.quant.stock.market.dto.MarketDailyDO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

public interface MarketDailyMapper {

    int countBySymbol(@Param("symbol") String symbol);

    List<MarketDailyDO> selectRange(@Param("symbol") String symbol,
                                    @Param("start") LocalDate start,
                                    @Param("end") LocalDate end);

    int batchUpsert(@Param("list") List<MarketDailyDO> list);
}
