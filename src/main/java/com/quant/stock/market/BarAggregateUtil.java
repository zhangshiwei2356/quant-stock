package com.quant.stock.market;

import com.quant.stock.market.dto.BarDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 由 1 分钟 K 聚合更大周期：5/15/30/60 分钟、日、周、月
 */
public final class BarAggregateUtil {

    private static final LocalTime AM_OPEN = LocalTime.of(9, 30);
    private static final LocalTime AM_CLOSE = LocalTime.of(11, 30);
    private static final LocalTime PM_OPEN = LocalTime.of(13, 0);
    private static final LocalTime PM_CLOSE = LocalTime.of(15, 0);

    private BarAggregateUtil() {
    }

    public enum Period {
        M5(5), M15(15), M30(30), M60(60), DAY(0), WEEK(0), MONTH(0);

        private final int minutes;

        Period(int minutes) {
            this.minutes = minutes;
        }

        public int getMinutes() {
            return minutes;
        }
    }

    public static List<BarDTO> aggregate(List<BarDTO> minuteBars, Period period) {
        if (minuteBars == null || minuteBars.isEmpty() || period == null) {
            return new ArrayList<BarDTO>();
        }
        List<BarDTO> sorted = minuteBars.stream()
                .sorted(Comparator.comparing(BarDTO::getBarBegin))
                .collect(Collectors.toList());

        switch (period) {
            case M5:
            case M15:
            case M30:
            case M60:
                return aggregateByMinutes(sorted, period.getMinutes());
            case DAY:
                return aggregateByKey(sorted, b -> b.getBarBegin().toLocalDate().toString());
            case WEEK:
                return aggregateByKey(sorted, b -> {
                    LocalDate d = b.getBarBegin().toLocalDate();
                    LocalDate monday = d.with(DayOfWeek.MONDAY);
                    return monday.toString();
                });
            case MONTH:
                return aggregateByKey(sorted, b -> {
                    LocalDate d = b.getBarBegin().toLocalDate();
                    return d.getYear() + "-" + d.getMonthValue();
                });
            default:
                return new ArrayList<BarDTO>();
        }
    }

    private static List<BarDTO> aggregateByMinutes(List<BarDTO> bars, int minutes) {
        Map<String, List<BarDTO>> groups = new LinkedHashMap<String, List<BarDTO>>();
        for (BarDTO bar : bars) {
            if (!isTradingMinute(bar.getBarBegin())) {
                continue;
            }
            int slot = tradingMinuteIndex(bar.getBarBegin()) / minutes;
            String key = bar.getBarBegin().toLocalDate() + "#" + slot;
            if (!groups.containsKey(key)) {
                groups.put(key, new ArrayList<BarDTO>());
            }
            groups.get(key).add(bar);
        }
        return mergeGroups(groups);
    }

    private static List<BarDTO> aggregateByKey(List<BarDTO> bars, Function<BarDTO, String> keyFn) {
        Map<String, List<BarDTO>> groups = new LinkedHashMap<String, List<BarDTO>>();
        for (BarDTO bar : bars) {
            if (!isTradingMinute(bar.getBarBegin())) {
                continue;
            }
            String key = keyFn.apply(bar);
            if (!groups.containsKey(key)) {
                groups.put(key, new ArrayList<BarDTO>());
            }
            groups.get(key).add(bar);
        }
        return mergeGroups(groups);
    }

    private static List<BarDTO> mergeGroups(Map<String, List<BarDTO>> groups) {
        List<BarDTO> result = new ArrayList<BarDTO>();
        for (List<BarDTO> group : groups.values()) {
            if (group.isEmpty()) {
                continue;
            }
            group.sort(Comparator.comparing(BarDTO::getBarBegin));
            BarDTO first = group.get(0);
            BarDTO last = group.get(group.size() - 1);
            BigDecimal high = first.getHigh();
            BigDecimal low = first.getLow();
            BigDecimal volume = BigDecimal.ZERO;
            for (BarDTO b : group) {
                if (b.getHigh().compareTo(high) > 0) {
                    high = b.getHigh();
                }
                if (b.getLow().compareTo(low) < 0) {
                    low = b.getLow();
                }
                volume = volume.add(b.getVolume());
            }
            result.add(BarDTO.builder()
                    .code(first.getCode())
                    .barBegin(first.getBarBegin())
                    .open(first.getOpen())
                    .high(high)
                    .low(low)
                    .close(last.getClose())
                    .volume(volume)
                    .build());
        }
        return result;
    }

    /** A股交易分钟：09:30-11:29、13:00-14:59 */
    public static boolean isTradingMinute(LocalDateTime t) {
        if (t == null) {
            return false;
        }
        DayOfWeek dow = t.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = t.toLocalTime();
        boolean am = !time.isBefore(AM_OPEN) && time.isBefore(AM_CLOSE);
        boolean pm = !time.isBefore(PM_OPEN) && time.isBefore(PM_CLOSE);
        return am || pm;
    }

    /** 当日交易分钟序号 0~239 */
    public static int tradingMinuteIndex(LocalDateTime t) {
        LocalTime time = t.toLocalTime();
        if (!time.isBefore(AM_OPEN) && time.isBefore(AM_CLOSE)) {
            return (int) java.time.Duration.between(AM_OPEN, time).toMinutes();
        }
        if (!time.isBefore(PM_OPEN) && time.isBefore(PM_CLOSE)) {
            return 120 + (int) java.time.Duration.between(PM_OPEN, time).toMinutes();
        }
        return -1;
    }

    /** 丢弃未闭合的最后一根K线 */
    public static List<BarDTO> filterClosedBars(List<BarDTO> bars) {
        if (bars == null || bars.isEmpty()) {
            return new ArrayList<BarDTO>();
        }
        List<BarDTO> closed = bars.stream()
                .filter(BarDTO::isClosedBar)
                .sorted(Comparator.comparing(BarDTO::getBarBegin))
                .collect(Collectors.toList());
        // 防御：若系统时钟与数据末尾对齐，默认再丢弃最后一根未确认K
        if (closed.size() > 1) {
            BarDTO last = closed.get(closed.size() - 1);
            if (!last.isClosedBar()) {
                closed = new ArrayList<BarDTO>(closed.subList(0, closed.size() - 1));
            }
        }
        return closed;
    }

    public static BigDecimal scale(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(4, RoundingMode.HALF_UP);
    }
}
