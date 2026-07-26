package com.quant.stock.market.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 股票基础信息持久化实体，对应表 {@code stock_basic}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockBasicDO {
    private Integer id;
    /** 证券代码 */
    private String symbol;
    /** 简称 */
    private String name;
    /** 市场板块编码（1 沪 / 2 深创等） */
    private Integer market;
    /** 所属行业 */
    private String industry;
    /** 上市日期 */
    private LocalDate listDate;
    /** 退市日期（未退市为空） */
    private LocalDate delistDate;
    /** 是否 ST：1 是 / 0 否 */
    private Integer isSt;
    /** 状态：1 正常 / 0 停用 */
    private Integer status;
}
