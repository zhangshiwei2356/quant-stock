package com.quant.stock.session;

/**
 * 持仓交易日态（脚手架最小集）。
 */
public enum HoldDayState {
    FLAT,
    HOLD_D0,
    HOLD_D1,
    HOLD_D2;

    public HoldDayState nextHold() {
        switch (this) {
            case FLAT:
                return HOLD_D0;
            case HOLD_D0:
                return HOLD_D1;
            case HOLD_D1:
                return HOLD_D2;
            default:
                return HOLD_D2;
        }
    }

    public int holdDayIndex() {
        switch (this) {
            case HOLD_D0:
                return 0;
            case HOLD_D1:
                return 1;
            case HOLD_D2:
                return 2;
            default:
                return -1;
        }
    }
}
