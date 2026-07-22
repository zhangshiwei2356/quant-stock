package com.quant.stock.risk;

import com.quant.stock.backtest.FillTimingHelper;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.mapper.StockBasicMapper;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.market.dto.StockBasicDO;
import com.quant.stock.strategy.IndicatorSignalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 开仓前置过滤：停牌/涨跌停/流动性/市值/静默时段。
 * 涨跌停相对<strong>上一交易日收盘</strong>判定（分钟序列不取相邻 bar）。
 */
@Service
public class OpenFilterService {

    private final QuantProperties props;

    @Autowired(required = false)
    private StockBasicMapper stockBasicMapper;

    private volatile Set<String> stCodesCache;
    private volatile long stCodesCacheAtMs;

    public OpenFilterService(QuantProperties props) {
        this.props = props;
    }

    public boolean canOpen(String stockCode, List<BarDTO> bars, int index) {
        if (bars == null || index < 1 || index >= bars.size()) {
            return false;
        }
        BarDTO cur = bars.get(index);
        if (FillTimingHelper.isMinuteSeries(bars, index) && isQuietPeriod(cur.getBarBegin())) {
            return false;
        }
        if (isLimitUpAt(bars, index) || isLimitDownAt(bars, index)) {
            return false;
        }
        if (isSuspended(cur)) {
            return false;
        }
        long avgVol = IndicatorSignalUtil.avgVolume(bars, index, 20);
        if (avgVol < props.getMinAvgVolume20()) {
            return false;
        }
        if (!props.isMarketCapFilterEnabled()) {
            return true;
        }
        BigDecimal mktCapYi = estimateMarketCapYi(stockCode, cur.getClose());
        return mktCapYi.compareTo(props.getMinMarketCapYi()) >= 0;
    }

    public boolean canExecuteOpenFill(String stockCode, List<BarDTO> bars, int index) {
        if (!FillTimingHelper.canFillPendingOnBar(bars, index)) {
            return false;
        }
        return canOpen(stockCode, bars, index);
    }

    public boolean isQuietPeriod(LocalDateTime t) {
        if (t == null) {
            return false;
        }
        LocalTime time = t.toLocalTime();
        if (props.isQuietOpenEnabled()
                && !time.isBefore(LocalTime.of(9, 30))
                && time.isBefore(FillTimingHelper.EFFECTIVE_OPEN)) {
            return true;
        }
        return props.isQuietCloseEnabled()
                && !time.isBefore(LocalTime.of(14, 45))
                && !time.isAfter(LocalTime.of(15, 0));
    }

    public BigDecimal prevTradingDayClose(List<BarDTO> bars, int index) {
        if (bars == null || index <= 0 || index >= bars.size() || bars.get(index) == null
                || bars.get(index).getBarBegin() == null) {
            return null;
        }
        LocalDate today = bars.get(index).getBarBegin().toLocalDate();
        for (int i = index - 1; i >= 0; i--) {
            BarDTO b = bars.get(i);
            if (b == null || b.getBarBegin() == null || b.getClose() == null) {
                continue;
            }
            if (b.getBarBegin().toLocalDate().isBefore(today)) {
                return b.getClose();
            }
        }
        return null;
    }

    public boolean isLimitUpAt(List<BarDTO> bars, int index) {
        return isLimitMove(bars, index, true);
    }

    public boolean isLimitDownAt(List<BarDTO> bars, int index) {
        return isLimitMove(bars, index, false);
    }

    /** @deprecated 请用 {@link #isLimitUpAt} */
    @Deprecated
    public boolean isLimitUp(BarDTO cur, BarDTO prev) {
        if (cur == null || prev == null) {
            return false;
        }
        return LimitBoardHelper.isLimitUp(cur, prev.getClose(), cur.getCode(), isSt(cur.getCode()));
    }

    /** @deprecated 请用 {@link #isLimitDownAt} */
    @Deprecated
    public boolean isLimitDown(BarDTO cur, BarDTO prev) {
        if (cur == null || prev == null) {
            return false;
        }
        return LimitBoardHelper.isLimitDown(cur, prev.getClose(), cur.getCode(), isSt(cur.getCode()));
    }

    public boolean isSuspended(BarDTO cur) {
        return cur == null || cur.getVolume() == null || cur.getVolume().compareTo(BigDecimal.ZERO) <= 0;
    }

    /** @deprecated 使用 {@link #isSuspended(BarDTO)} */
    @Deprecated
    public boolean isSuspended(BarDTO cur, BarDTO prev) {
        return isSuspended(cur);
    }

    private boolean isLimitMove(List<BarDTO> bars, int index, boolean up) {
        if (bars == null || index < 0 || index >= bars.size()) {
            return false;
        }
        BarDTO cur = bars.get(index);
        BigDecimal ref = prevTradingDayClose(bars, index);
        if (ref == null && index > 0 && bars.get(index - 1) != null) {
            ref = bars.get(index - 1).getClose();
        }
        String code = cur != null && StringUtils.hasText(cur.getCode())
                ? cur.getCode() : null;
        boolean st = isSt(code);
        if (up) {
            return LimitBoardHelper.isLimitUp(cur, ref, code, st);
        }
        return LimitBoardHelper.isLimitDown(cur, ref, code, st);
    }

    public boolean isSt(String code) {
        if (!StringUtils.hasText(code) || stockBasicMapper == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Set<String> cache = stCodesCache;
        if (cache == null || now - stCodesCacheAtMs > 60_000L) {
            Set<String> next = new HashSet<String>();
            try {
                List<StockBasicDO> all = stockBasicMapper.selectAll();
                if (all != null) {
                    for (StockBasicDO b : all) {
                        if (b != null && b.getIsSt() != null && b.getIsSt() == 1
                                && StringUtils.hasText(b.getSymbol())) {
                            next.add(b.getSymbol().trim());
                        }
                    }
                }
            } catch (Exception ignored) {
                if (cache != null) {
                    next = cache;
                }
            }
            stCodesCache = next;
            stCodesCacheAtMs = now;
            cache = next;
        }
        return cache.contains(code.trim());
    }

    /** 流通市值（亿元）= 价格 × 流通股本（亿股） */
    public BigDecimal estimateMarketCapYi(String code, BigDecimal price) {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        return price.multiply(resolveSharesYi(code)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveSharesYi(String code) {
        Map<String, BigDecimal> map = props.getFloatSharesYi();
        if (map != null && code != null && map.containsKey(code)) {
            BigDecimal v = map.get(code);
            if (v != null && v.compareTo(BigDecimal.ZERO) > 0) {
                return v;
            }
        }
        if (code != null && code.startsWith("688")) {
            return new BigDecimal("40");
        }
        if (code != null && code.startsWith("6")) {
            return new BigDecimal("120");
        }
        if (code != null && code.startsWith("3")) {
            return new BigDecimal("80");
        }
        return new BigDecimal("100");
    }
}
