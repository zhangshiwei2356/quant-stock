package com.quant.stock.mapper;

import com.quant.stock.market.dto.FactorDailyDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface FactorDailyMapper {

    int deleteBySymbol(@Param("symbol") String symbol);

    int batchUpsert(@Param("list") List<FactorDailyDO> list);

    /** 各标的最新一日因子（用于入池粗过滤） */
    List<FactorDailyDO> selectLatestBySymbols(@Param("symbols") List<String> symbols);
}
