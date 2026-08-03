package com.quant.stock.kuangrui;

import com.quant.stock.market.dto.BarDTO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单标的当前分钟桶：用 L1 最新价滚动 OHLC，用日累计量/额差分得到本分钟量额。
 */
public final class MdsMinuteBucket {

    private final String code;
    private LocalDateTime barBegin;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private long volume;
    private BigDecimal amount;
    private long lastCumVolume = -1L;
    private long lastCumAmountMilli = -1L;

    public MdsMinuteBucket(String code) {
        this.code = code;
        this.amount = BigDecimal.ZERO;
    }

    public String getCode() {
        return code;
    }

    public LocalDateTime getBarBegin() {
        return barBegin;
    }

    /**
     * 吃入一笔已换算为「元」的快照。
     *
     * @param minuteBegin 本分钟起始
     * @param lastPxYuan  最新价（元）
     * @param cumVolume   日累计成交量（股，柜台原值）
     * @param cumAmountMilli 日累计成交额（毫）
     * @return 若分钟切换，返回已闭合的上一分钟 {@link BarDTO}；否则 null
     */
    public BarDTO onTick(LocalDateTime minuteBegin, BigDecimal lastPxYuan,
                         long cumVolume, long cumAmountMilli) {
        if (minuteBegin == null || lastPxYuan == null || lastPxYuan.signum() <= 0) {
            return null;
        }
        BarDTO closed = null;
        if (barBegin == null) {
            startNew(minuteBegin, lastPxYuan);
        } else if (!barBegin.equals(minuteBegin)) {
            closed = toBar();
            startNew(minuteBegin, lastPxYuan);
            lastCumVolume = -1L;
            lastCumAmountMilli = -1L;
        } else {
            if (lastPxYuan.compareTo(high) > 0) {
                high = lastPxYuan;
            }
            if (lastPxYuan.compareTo(low) < 0) {
                low = lastPxYuan;
            }
            close = lastPxYuan;
        }
        applyCumDelta(cumVolume, cumAmountMilli);
        return closed;
    }

    /** 强制导出当前桶（未闭合分钟也可落库，供 pull 刷新）。 */
    public BarDTO snapshot() {
        if (barBegin == null || close == null) {
            return null;
        }
        return toBar();
    }

    private void startNew(LocalDateTime minuteBegin, BigDecimal px) {
        barBegin = minuteBegin;
        open = px;
        high = px;
        low = px;
        close = px;
        volume = 0L;
        amount = BigDecimal.ZERO;
    }

    private void applyCumDelta(long cumVolume, long cumAmountMilli) {
        if (cumVolume >= 0L && lastCumVolume >= 0L && cumVolume >= lastCumVolume) {
            volume += (cumVolume - lastCumVolume);
        }
        if (cumAmountMilli >= 0L && lastCumAmountMilli >= 0L && cumAmountMilli >= lastCumAmountMilli) {
            BigDecimal delta = KuangruiPriceScale.toYuan(cumAmountMilli - lastCumAmountMilli);
            if (delta != null) {
                amount = amount.add(delta);
            }
        }
        if (cumVolume >= 0L) {
            lastCumVolume = cumVolume;
        }
        if (cumAmountMilli >= 0L) {
            lastCumAmountMilli = cumAmountMilli;
        }
    }

    private BarDTO toBar() {
        return BarDTO.builder()
                .code(code)
                .barBegin(barBegin)
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .volume(BigDecimal.valueOf(Math.max(0L, volume)))
                .amount(amount == null || amount.signum() <= 0 ? null : amount)
                .periodMinutes(1)
                .build();
    }
}
