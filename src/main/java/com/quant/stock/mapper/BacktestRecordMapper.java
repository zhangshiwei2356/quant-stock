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

    /** 按多个 strategy_id（规范 id + 历史别名）查询摘要。 */
    List<BtBacktestRecordDO> selectSummaryByStrategyIds(@Param("strategyIds") List<String> strategyIds,
                                                        @Param("kind") String kind);

    /** 按 record_id 查询全列。 */
    BtBacktestRecordDO selectByRecordId(@Param("recordId") String recordId);

    /** 统计 strategy_id 为空或空串的记录数。 */
    long countUnknownStrategy();

    /** 库内出现过的 strategy_id（含 NULL 用空串不便；仅非空去重）。 */
    List<String> selectDistinctStrategyIds();

    /** 将空白 strategy_id 写为默认注册 id。 */
    int updateBlankStrategyId(@Param("strategyId") String strategyId);

    /** 将 fromIds 中的取值统一改为 toId。 */
    int updateStrategyIdAliases(@Param("fromIds") List<String> fromIds,
                                @Param("toId") String toId);
}
