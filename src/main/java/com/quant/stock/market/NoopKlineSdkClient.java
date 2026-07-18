package com.quant.stock.market;

import com.quant.stock.market.dto.BarDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class NoopKlineSdkClient implements KlineSdkClient {
    @Override
    public List<BarDTO> fetchMinuteBars(String stockCode) {
        return new ArrayList<BarDTO>();
    }
}
