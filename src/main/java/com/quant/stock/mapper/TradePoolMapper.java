package com.quant.stock.mapper;

import com.quant.stock.pool.dto.TradePoolDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TradePoolMapper {

    List<TradePoolDO> selectActive();

    List<TradePoolDO> selectAll();

    int countActive();

    int deactivateAll();

    int deactivateBySymbol(@Param("symbol") String symbol);

    int upsert(TradePoolDO row);

    TradePoolDO selectBySymbol(@Param("symbol") String symbol);
}
