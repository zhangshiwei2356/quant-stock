package com.quant.stock.market;

import com.quant.stock.market.dto.BarDTO;

import java.util.List;

/**
 * 第三方行情 SDK / HTTP 适配点。默认 {@link NoopKlineSdkClient} 返回空，由上层回退 mock/json。
 */
public interface KlineSdkClient {

    /**
     * 拉取 1 分钟 K；无数据返回空列表（勿返回 null）。
     */
    List<BarDTO> fetchMinuteBars(String stockCode);
}
