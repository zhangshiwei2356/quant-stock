package com.quant.stock.mapper;

import com.quant.stock.pool.dto.TradePoolReportDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface TradePoolReportMapper {

    int insert(TradePoolReportDO row);

    TradePoolReportDO selectById(@Param("id") Long id);

    TradePoolReportDO selectLatestBySymbol(@Param("symbol") String symbol);

    List<Map<String, Object>> selectBatchSummaries(@Param("limit") int limit);

    List<TradePoolReportDO> selectByBatchId(@Param("batchId") String batchId);
}
