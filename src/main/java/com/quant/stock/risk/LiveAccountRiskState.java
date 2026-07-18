package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
import org.springframework.stereotype.Component;

/**
 * 实盘账户风控（单例）；回测请使用 new AccountRiskState(props)
 */
@Component
public class LiveAccountRiskState extends AccountRiskState {
    public LiveAccountRiskState(QuantProperties props) {
        super(props);
    }
}
