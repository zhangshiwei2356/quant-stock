package com.quant.stock.strategy;

import org.springframework.stereotype.Component;

/** 金叉对照画像：趋势+放量，RSI&lt;55。 */
@Component
public class MaCrossBalancedStrategy extends AbstractMaCrossProfileStrategy {
    @Override
    protected MaCrossFilterProfile profile() {
        return MaCrossFilterProfile.BALANCED;
    }
}
