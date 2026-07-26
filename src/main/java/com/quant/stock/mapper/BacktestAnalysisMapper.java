package com.quant.stock.mapper;

import com.quant.stock.backtest.dto.BtBacktestAnalysisDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 回测分析明细 Mapper：与 bt_backtest_record 一一对应的分析 JSON 持久化。
 */
public interface BacktestAnalysisMapper {

    /** 插入一条回测分析记录。 */
    int insert(BtBacktestAnalysisDO row);

    /** 按 record_id 查询唯一分析行。 */
    BtBacktestAnalysisDO selectByRecordId(@Param("recordId") String recordId);

    /** 按 kind 与可选 stockCode 列出分析记录。 */
    List<BtBacktestAnalysisDO> selectByKind(@Param("kind") String kind,
                                            @Param("stockCode") String stockCode);

    /** 删除某股票相关的全部单股分析。 */
    int deleteSingleByCode(@Param("stockCode") String stockCode);

    /** 按 kind 清空该类型的全部分析。 */
    int deleteAllByKind(@Param("kind") String kind);
}
