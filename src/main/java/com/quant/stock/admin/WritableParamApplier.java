package com.quant.stock.admin;

import com.quant.stock.config.QuantProperties;

import java.math.BigDecimal;

/**
 * 将白名单键值应用到 {@link QuantProperties}（全局热写与策略包叠层共用）。
 */
public final class WritableParamApplier {

    private WritableParamApplier() {
    }

    public static void apply(QuantProperties props, String key, String raw) {
        if (props == null) {
            throw new IllegalArgumentException("props 不能为空");
        }
        String type = WritableParamKeys.typeOf(key);
        if (type == null) {
            throw new IllegalArgumentException("非白名单键");
        }
        if ("bool".equals(type)) {
            boolean v = parseBool(raw);
            if ("trendFilterEnabled".equals(key)) {
                props.setTrendFilterEnabled(v);
            } else if ("volumeFilterEnabled".equals(key)) {
                props.setVolumeFilterEnabled(v);
            } else if ("adxFilterEnabled".equals(key)) {
                props.setAdxFilterEnabled(v);
            } else if ("pyramidEnabled".equals(key)) {
                props.setPyramidEnabled(v);
            } else if ("stopLossEnabled".equals(key)) {
                props.setStopLossEnabled(v);
            } else if ("trailingStopEnabled".equals(key)) {
                props.setTrailingStopEnabled(v);
            } else if ("limitPriceProtectEnabled".equals(key)) {
                props.setLimitPriceProtectEnabled(v);
            } else if ("nextBarOpenFill".equals(key)) {
                props.setNextBarOpenFill(v);
            } else if ("poolRebuildRefreshFactors".equals(key)) {
                props.setPoolRebuildRefreshFactors(v);
            } else if ("poolRebuildFullBacktest".equals(key)) {
                props.setPoolRebuildFullBacktest(v);
            } else {
                throw new IllegalArgumentException("未绑定 bool 键");
            }
            return;
        }
        if ("int".equals(type)) {
            int v = Integer.parseInt(raw.trim());
            if ("trendMaPeriod".equals(key)) {
                props.setTrendMaPeriod(v);
            } else if ("maxHoldTradingDays".equals(key)) {
                props.setMaxHoldTradingDays(v);
            } else if ("consecutiveLossLimit".equals(key)) {
                props.setConsecutiveLossLimit(v);
            } else {
                throw new IllegalArgumentException("未绑定 int 键");
            }
            return;
        }
        if ("decimal".equals(type)) {
            BigDecimal v = new BigDecimal(raw.trim());
            if ("volumeConfirmRatio".equals(key)) {
                props.setVolumeConfirmRatio(v);
            } else if ("adxMin".equals(key)) {
                props.setAdxMin(v);
            } else if ("adxChopMax".equals(key)) {
                props.setAdxChopMax(v);
            } else if ("rsiBuyMax".equals(key)) {
                props.setRsiBuyMax(v);
            } else if ("maxSinglePosition".equals(key)) {
                props.setMaxSinglePosition(v);
            } else if ("maxTotalPosition".equals(key)) {
                props.setMaxTotalPosition(v);
            } else if ("pyramidFirst".equals(key)) {
                props.setPyramidFirst(v);
            } else if ("pyramidSecond".equals(key)) {
                props.setPyramidSecond(v);
            } else if ("pyramidThird".equals(key)) {
                props.setPyramidThird(v);
            } else if ("pyramidAddPct".equals(key)) {
                props.setPyramidAddPct(v);
            } else if ("atrStopMultiplier".equals(key)) {
                props.setAtrStopMultiplier(v);
            } else if ("hardStopCapitalPct".equals(key)) {
                props.setHardStopCapitalPct(v);
            } else if ("trailingAtrMultiplier".equals(key)) {
                props.setTrailingAtrMultiplier(v);
            } else if ("feeRate".equals(key)) {
                props.setFeeRate(v);
            } else if ("slipPoint".equals(key)) {
                props.setSlipPoint(v);
            } else if ("maxParticipationAdv".equals(key)) {
                props.setMaxParticipationAdv(v);
            } else if ("dailyLossLimitPct".equals(key)) {
                props.setDailyLossLimitPct(v);
            } else if ("drawdownReducePct".equals(key)) {
                props.setDrawdownReducePct(v);
            } else if ("drawdownHaltPct".equals(key)) {
                props.setDrawdownHaltPct(v);
            } else {
                throw new IllegalArgumentException("未绑定 decimal 键");
            }
            return;
        }
        throw new IllegalArgumentException("未知类型 " + type);
    }

    public static String formatStored(String key, String raw) {
        String type = WritableParamKeys.typeOf(key);
        if ("bool".equals(type)) {
            return String.valueOf(parseBool(raw));
        }
        if ("int".equals(type)) {
            return String.valueOf(Integer.parseInt(raw.trim()));
        }
        if ("decimal".equals(type)) {
            return new BigDecimal(raw.trim()).toPlainString();
        }
        return raw == null ? "" : raw.trim();
    }

    public static boolean parseBool(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("布尔值不能为空");
        }
        String s = raw.trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s)) {
            return true;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "off".equals(s)) {
            return false;
        }
        throw new IllegalArgumentException("非法布尔: " + raw);
    }

    /** 从 props 读取白名单键当前值（字符串）。 */
    public static String read(QuantProperties props, String key) {
        if (props == null || key == null) {
            return null;
        }
        if ("trendFilterEnabled".equals(key)) {
            return String.valueOf(props.isTrendFilterEnabled());
        }
        if ("trendMaPeriod".equals(key)) {
            return String.valueOf(props.getTrendMaPeriod());
        }
        if ("volumeFilterEnabled".equals(key)) {
            return String.valueOf(props.isVolumeFilterEnabled());
        }
        if ("volumeConfirmRatio".equals(key)) {
            return props.getVolumeConfirmRatio() == null ? null : props.getVolumeConfirmRatio().toPlainString();
        }
        if ("adxFilterEnabled".equals(key)) {
            return String.valueOf(props.isAdxFilterEnabled());
        }
        if ("adxMin".equals(key)) {
            return props.getAdxMin() == null ? null : props.getAdxMin().toPlainString();
        }
        if ("adxChopMax".equals(key)) {
            return props.getAdxChopMax() == null ? null : props.getAdxChopMax().toPlainString();
        }
        if ("rsiBuyMax".equals(key)) {
            return props.getRsiBuyMax() == null ? null : props.getRsiBuyMax().toPlainString();
        }
        if ("maxSinglePosition".equals(key)) {
            return props.getMaxSinglePosition() == null ? null : props.getMaxSinglePosition().toPlainString();
        }
        if ("maxTotalPosition".equals(key)) {
            return props.getMaxTotalPosition() == null ? null : props.getMaxTotalPosition().toPlainString();
        }
        if ("pyramidEnabled".equals(key)) {
            return String.valueOf(props.isPyramidEnabled());
        }
        if ("pyramidFirst".equals(key)) {
            return props.getPyramidFirst() == null ? null : props.getPyramidFirst().toPlainString();
        }
        if ("pyramidSecond".equals(key)) {
            return props.getPyramidSecond() == null ? null : props.getPyramidSecond().toPlainString();
        }
        if ("pyramidThird".equals(key)) {
            return props.getPyramidThird() == null ? null : props.getPyramidThird().toPlainString();
        }
        if ("pyramidAddPct".equals(key)) {
            return props.getPyramidAddPct() == null ? null : props.getPyramidAddPct().toPlainString();
        }
        if ("stopLossEnabled".equals(key)) {
            return String.valueOf(props.isStopLossEnabled());
        }
        if ("atrStopMultiplier".equals(key)) {
            return props.getAtrStopMultiplier() == null ? null : props.getAtrStopMultiplier().toPlainString();
        }
        if ("hardStopCapitalPct".equals(key)) {
            return props.getHardStopCapitalPct() == null ? null : props.getHardStopCapitalPct().toPlainString();
        }
        if ("trailingStopEnabled".equals(key)) {
            return String.valueOf(props.isTrailingStopEnabled());
        }
        if ("trailingAtrMultiplier".equals(key)) {
            return props.getTrailingAtrMultiplier() == null ? null : props.getTrailingAtrMultiplier().toPlainString();
        }
        if ("maxHoldTradingDays".equals(key)) {
            return String.valueOf(props.getMaxHoldTradingDays());
        }
        if ("feeRate".equals(key)) {
            return props.getFeeRate() == null ? null : props.getFeeRate().toPlainString();
        }
        if ("slipPoint".equals(key)) {
            return props.getSlipPoint() == null ? null : props.getSlipPoint().toPlainString();
        }
        if ("maxParticipationAdv".equals(key)) {
            return props.getMaxParticipationAdv() == null ? null : props.getMaxParticipationAdv().toPlainString();
        }
        if ("limitPriceProtectEnabled".equals(key)) {
            return String.valueOf(props.isLimitPriceProtectEnabled());
        }
        if ("nextBarOpenFill".equals(key)) {
            return String.valueOf(props.isNextBarOpenFill());
        }
        if ("poolRebuildRefreshFactors".equals(key)) {
            return String.valueOf(props.isPoolRebuildRefreshFactors());
        }
        if ("poolRebuildFullBacktest".equals(key)) {
            return String.valueOf(props.isPoolRebuildFullBacktest());
        }
        if ("dailyLossLimitPct".equals(key)) {
            return props.getDailyLossLimitPct() == null ? null : props.getDailyLossLimitPct().toPlainString();
        }
        if ("consecutiveLossLimit".equals(key)) {
            return String.valueOf(props.getConsecutiveLossLimit());
        }
        if ("drawdownReducePct".equals(key)) {
            return props.getDrawdownReducePct() == null ? null : props.getDrawdownReducePct().toPlainString();
        }
        if ("drawdownHaltPct".equals(key)) {
            return props.getDrawdownHaltPct() == null ? null : props.getDrawdownHaltPct().toPlainString();
        }
        return null;
    }
}
