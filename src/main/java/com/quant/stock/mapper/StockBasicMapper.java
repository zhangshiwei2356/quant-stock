package com.quant.stock.mapper;

import com.quant.stock.market.dto.StockBasicDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StockBasicMapper {

    int countAll();

    List<StockBasicDO> selectAll();

    int upsert(StockBasicDO row);
}
