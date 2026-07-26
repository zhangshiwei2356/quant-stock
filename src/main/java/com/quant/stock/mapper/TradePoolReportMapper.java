package com.quant.stock.mapper;

import com.quant.stock.pool.dto.TradePoolReportDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 目标池扫描报告 Mapper：trade_pool_report 批次与单票分析落库。
 */
public interface TradePoolReportMapper {

    /** 插入一条扫描/分析报告。 */
    int insert(TradePoolReportDO row);

    /** 按主键 id 读取报告。 */
    TradePoolReportDO selectById(@Param("id") Long id);

    /** 查询某 symbol 最近一条报告。 */
    TradePoolReportDO selectLatestBySymbol(@Param("symbol") String symbol);

    /** 按批次聚合摘要，限制返回批次数。 */
    List<Map<String, Object>> selectBatchSummaries(@Param("limit") int limit);

    /** 列出某一 batch_id 下的全部报告明细。 */
    List<TradePoolReportDO> selectByBatchId(@Param("batchId") String batchId);
}
