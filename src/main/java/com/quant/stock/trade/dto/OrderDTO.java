package com.quant.stock.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 委托传输对象：网关内存态与 {@code trade_orders} 落库共用字段语义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDTO {

    /** 买卖方向 */
    public enum Side {
        BUY, SELL
    }

    /** 委托生命周期状态 */
    public enum Status {
        PENDING, SUBMITTED, PARTIAL, FILLED, CANCELLED, REJECTED
    }

    /** 标的代码 */
    private String stockCode;
    private Side side;
    /** 委托价格 */
    private BigDecimal price;
    /** 委托数量（股） */
    private Integer volume;
    /** 系统委托号 */
    private String orderId;
    /** 客户端幂等键 */
    private String clientOrderId;
    private Status status;
    /** 已成交数量；SUBMITTED=0，FILLED=volume，PARTIAL=部分 */
    private Integer filledVolume;
}
