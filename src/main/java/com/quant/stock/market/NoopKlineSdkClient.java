package com.quant.stock.market;

import com.quant.stock.market.dto.BarDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认 K 线 SDK 桩：不拉取外部行情，始终返回空列表，由 {@link MarketDataService} 回退 JSON/mock。
 */
@Component
public class NoopKlineSdkClient implements KlineSdkClient {
    /** 空实现：恒返回空列表（未接外部行情 SDK）。 */
    @Override
    public List<BarDTO> fetchMinuteBars(String stockCode) {
        return new ArrayList<BarDTO>();
    }
}
