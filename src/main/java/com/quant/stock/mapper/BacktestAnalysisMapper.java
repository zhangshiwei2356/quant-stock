package com.quant.stock.mapper;

import com.quant.stock.backtest.dto.BtBacktestAnalysisDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface BacktestAnalysisMapper {

    int insert(BtBacktestAnalysisDO row);

    BtBacktestAnalysisDO selectByRecordId(@Param("recordId") String recordId);

    List<BtBacktestAnalysisDO> selectByKind(@Param("kind") String kind,
                                            @Param("stockCode") String stockCode);

    int deleteSingleByCode(@Param("stockCode") String stockCode);

    int deleteAllByKind(@Param("kind") String kind);
}
