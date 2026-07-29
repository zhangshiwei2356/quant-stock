package com.quant.stock.strategy;

import org.springframework.stereotype.Component;

/** 金叉对照画像：仅 MA60 趋势过滤。 */
@Component
public class MaCrossTrendStrategy extends AbstractMaCrossProfileStrategy {
    @Override
    protected MaCrossFilterProfile profile() {
        return MaCrossFilterProfile.TREND;
    }
}
