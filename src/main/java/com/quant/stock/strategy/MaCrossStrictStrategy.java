package com.quant.stock.strategy;

import org.springframework.stereotype.Component;

/** 金叉对照画像：趋势+放量+ADX，RSI&lt;50。 */
@Component
public class MaCrossStrictStrategy extends AbstractMaCrossProfileStrategy {
    @Override
    protected MaCrossFilterProfile profile() {
        return MaCrossFilterProfile.STRICT;
    }
}
