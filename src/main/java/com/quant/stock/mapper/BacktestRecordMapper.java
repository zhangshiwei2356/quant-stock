package com.quant.stock.mapper;

import com.quant.stock.backtest.dto.BtBacktestRecordDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface BacktestRecordMapper {

    int insert(BtBacktestRecordDO row);

    List<BtBacktestRecordDO> selectByKind(@Param("kind") String kind,
                                          @Param("stockCode") String stockCode);

    int deleteSingleByCode(@Param("stockCode") String stockCode);

    int deleteAllByKind(@Param("kind") String kind);
}
