package com.quant.stock.mapper;

import com.quant.stock.backtest.dto.BtBacktestRecordDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 回测历史记录 Mapper：bt_backtest_record 单股/组合结果持久化。
 */
public interface BacktestRecordMapper {

    /** 插入一条回测历史记录。 */
    int insert(BtBacktestRecordDO row);

    /** 按 kind（single/portfolio）及可选 stockCode 筛选历史列表。 */
    List<BtBacktestRecordDO> selectByKind(@Param("kind") String kind,
                                          @Param("stockCode") String stockCode);

    /** 删除某股票的全部单股回测历史。 */
    int deleteSingleByCode(@Param("stockCode") String stockCode);

    /** 按 kind 清空该类型的全部回测历史。 */
    int deleteAllByKind(@Param("kind") String kind);

    /** 按注册策略 id 查询摘要列表（不含 trades/stock_results JSON）。 */
    List<BtBacktestRecordDO> selectSummaryByStrategyId(@Param("strategyId") String strategyId,
                                                       @Param("kind") String kind);

    /** 按 record_id 查询全列。 */
    BtBacktestRecordDO selectByRecordId(@Param("recordId") String recordId);

    /** 统计 strategy_id 为空或空串的记录数。 */
    long countUnknownStrategy();
}
