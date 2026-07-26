package com.quant.stock.mapper;

import com.quant.stock.pool.dto.TradePoolDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 唯一目标池 Mapper：trade_pool 活跃入选与人工移出。
 */
public interface TradePoolMapper {

    /** 查询 active=1 的当前目标池成员。 */
    List<TradePoolDO> selectActive();

    /** 查询 trade_pool 全部行（含历史 inactive）。 */
    List<TradePoolDO> selectAll();

    /** 统计当前活跃目标池数量。 */
    int countActive();

    /** 将全部行的 active 置为 0（扫描覆盖前清空）。 */
    int deactivateAll();

    /** 将指定 symbol 的目标池行置为 inactive。 */
    int deactivateBySymbol(@Param("symbol") String symbol);

    /** 插入或更新目标池行（含得分与批次信息）。 */
    int upsert(TradePoolDO row);

    /** 按 symbol 查询目标池行（不论 active）。 */
    TradePoolDO selectBySymbol(@Param("symbol") String symbol);
}
