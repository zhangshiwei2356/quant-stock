package com.quant.stock.mapper;

import com.quant.stock.pool.dto.TradePoolReportDO;
import org.apache.ibatis.annotations.Param;

public interface TradePoolReportMapper {

    int insert(TradePoolReportDO row);

    TradePoolReportDO selectById(@Param("id") Long id);

    TradePoolReportDO selectLatestBySymbol(@Param("symbol") String symbol);
}
