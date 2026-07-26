package com.quant.stock.mapper;

import com.quant.stock.market.dto.FactorDailyDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 日频因子表 Mapper：写入与按标的批量查询最新因子。
 */
public interface FactorDailyMapper {

    /** 按 symbol 删除 factor_daily 中全部历史行。 */
    int deleteBySymbol(@Param("symbol") String symbol);

    /** 批量插入日因子，主键冲突时更新因子字段。 */
    int batchUpsert(@Param("list") List<FactorDailyDO> list);

    /** 按 symbol 列表查询各标的在 factor_daily 中的最新一日因子。 */
    List<FactorDailyDO> selectLatestBySymbols(@Param("symbols") List<String> symbols);
}
