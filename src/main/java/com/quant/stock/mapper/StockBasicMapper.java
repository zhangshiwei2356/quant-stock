package com.quant.stock.mapper;

import com.quant.stock.market.dto.StockBasicDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 股票基础信息 Mapper：stock_basic 全市场列表与 upsert。
 */
public interface StockBasicMapper {

    /** 统计 stock_basic 表总行数。 */
    int countAll();

    /** 查询全部上市标的基础信息。 */
    List<StockBasicDO> selectAll();

    /** 插入或更新单条 stock_basic（代码为主键）。 */
    int upsert(StockBasicDO row);
}
