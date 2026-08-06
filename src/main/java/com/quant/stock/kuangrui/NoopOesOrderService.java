package com.quant.stock.kuangrui;

import com.quant.stock.trade.dto.OrderDTO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认 OES 报撤桩：恒不可用。
 * <p>
 * 始终注册；启用宽睿且 {@code oes.enabled} 时由 {@code KuangruiOesReadonlyService}（{@code @Primary}）优先。
 * </p>
 */
@Service
public class NoopOesOrderService implements OesOrderService {

    @Override
    public boolean isOrderLive() {
        return false;
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("orderLive", false);
        m.put("impl", "noop");
        m.put("hint", "默认关闭。启用需：mvn -Pkuangrui + quant.kuangrui.enabled/oes.enabled/oes.order-enabled=true"
                + " + trade-mode=sdk + OES 配置账号");
        return m;
    }

    @Override
    public OesPlaceResult placeLimit(String stockCode, OrderDTO.Side side, BigDecimal priceYuan, int qty,
                                     int clSeqNo, String clientOrderId) {
        return OesPlaceResult.fail(clSeqNo, "OES 报单未启用");
    }

    @Override
    public boolean cancelByClSeqNo(int origClSeqNo, String stockCode) {
        return false;
    }

    @Override
    public List<OesOrderEvent> pollEvents() {
        return Collections.emptyList();
    }
}
