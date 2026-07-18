package com.quant.stock.market.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockBasicDO {
    private Integer id;
    private String symbol;
    private String name;
    private Integer market;
    private String industry;
    private LocalDate listDate;
    private LocalDate delistDate;
    private Integer isSt;
    private Integer status;
}
