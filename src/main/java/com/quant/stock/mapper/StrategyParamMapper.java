package com.quant.stock.mapper;

import com.quant.stock.admin.dto.StrategyParamDO;
import org.apache.ibatis.annotations.Param;

/**
 * 策略稀疏参数包 Mapper。
 */
public interface StrategyParamMapper {

    StrategyParamDO selectByStrategyId(@Param("strategyId") String strategyId);

    /** 插入或更新；冲突时覆盖 JSON 且 version+1。 */
    int upsert(StrategyParamDO row);

    /** 按 strategy_id 删除稀疏参数包。 */
    int deleteByStrategyId(@Param("strategyId") String strategyId);
}
