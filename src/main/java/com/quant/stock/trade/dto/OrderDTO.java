package com.quant.stock.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDTO {

    public enum Side {
        BUY, SELL
    }

    public enum Status {
        PENDING, SUBMITTED, PARTIAL, FILLED, CANCELLED, REJECTED
    }

    private String stockCode;
    private Side side;
    private BigDecimal price;
    private Integer volume;
    private String orderId;
    /** 客户端幂等键 */
    private String clientOrderId;
    private Status status;
    /** 已成交数量；SUBMITTED=0，FILLED=volume，PARTIAL=部分 */
    private Integer filledVolume;
}
