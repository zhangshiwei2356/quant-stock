package com.quant.stock.strategy;

import org.springframework.stereotype.Component;

/** 金叉对照画像：仅放量确认（1.5×）。 */
@Component
public class MaCrossVolumeStrategy extends AbstractMaCrossProfileStrategy {
    @Override
    protected MaCrossFilterProfile profile() {
        return MaCrossFilterProfile.VOLUME;
    }
}
