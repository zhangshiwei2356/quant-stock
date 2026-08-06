package com.quant.stock.kuangrui;

import com.quant.stock.trade.dto.OrderDTO;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 宽睿 OES 报撤门面（M3）。
 * <p>
 * 默认 Noop；真实客户端仅 {@code -Pkuangrui} 且
 * {@code quant.kuangrui.enabled}+{@code oes.enabled}+{@code oes.order-enabled} 时 {@link #isOrderLive()} 为 true。
 * </p>
 */
public interface OesOrderService {

    /** 是否允许真实报撤（jar + 三闸全开）。 */
    boolean isOrderLive();

    Map<String, Object> status();

    /**
     * 限价报单。
     *
     * @param clSeqNo 客户端递增流水（同一 clEnvId 下唯一）
     * @return 结果；失败时 {@link OesPlaceResult#isAccepted()} 为 false
     */
    OesPlaceResult placeLimit(String stockCode, OrderDTO.Side side, BigDecimal priceYuan, int qty,
                              int clSeqNo, String clientOrderId);

    /**
     * 按原始 clSeqNo 撤单。
     *
     * @return true 撤单请求已发出（非柜台最终确认）
     */
    boolean cancelByClSeqNo(int origClSeqNo, String stockCode);

    /**
     * 取出自上次以来缓存的委托/成交回报事件（供 sync-orders 推进状态）。
     */
    List<OesOrderEvent> pollEvents();

    /** 报单结果。 */
    final class OesPlaceResult {
        private final boolean accepted;
        private final int clSeqNo;
        private final long clOrdId;
        private final String message;

        public OesPlaceResult(boolean accepted, int clSeqNo, long clOrdId, String message) {
            this.accepted = accepted;
            this.clSeqNo = clSeqNo;
            this.clOrdId = clOrdId;
            this.message = message;
        }

        public static OesPlaceResult ok(int clSeqNo, long clOrdId) {
            return new OesPlaceResult(true, clSeqNo, clOrdId, null);
        }

        public static OesPlaceResult fail(int clSeqNo, String message) {
            return new OesPlaceResult(false, clSeqNo, 0L, message);
        }

        public boolean isAccepted() {
            return accepted;
        }

        public int getClSeqNo() {
            return clSeqNo;
        }

        public long getClOrdId() {
            return clOrdId;
        }

        public String getMessage() {
            return message;
        }
    }

    /** 回报事件（委托确认或成交）。 */
    final class OesOrderEvent {
        public enum Kind {
            ORDER, TRADE
        }

        private final Kind kind;
        private final int clSeqNo;
        private final long clOrdId;
        private final String code;
        private final int ordStatus;
        private final int cumQty;
        private final int trdQty;
        private final BigDecimal trdPrice;

        public OesOrderEvent(Kind kind, int clSeqNo, long clOrdId, String code,
                             int ordStatus, int cumQty, int trdQty, BigDecimal trdPrice) {
            this.kind = kind;
            this.clSeqNo = clSeqNo;
            this.clOrdId = clOrdId;
            this.code = code;
            this.ordStatus = ordStatus;
            this.cumQty = cumQty;
            this.trdQty = trdQty;
            this.trdPrice = trdPrice;
        }

        public Kind getKind() {
            return kind;
        }

        public int getClSeqNo() {
            return clSeqNo;
        }

        public long getClOrdId() {
            return clOrdId;
        }

        public String getCode() {
            return code;
        }

        public int getOrdStatus() {
            return ordStatus;
        }

        public int getCumQty() {
            return cumQty;
        }

        public int getTrdQty() {
            return trdQty;
        }

        public BigDecimal getTrdPrice() {
            return trdPrice;
        }
    }
}
