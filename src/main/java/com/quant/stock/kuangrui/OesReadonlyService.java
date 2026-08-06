package com.quant.stock.kuangrui;

import java.util.List;
import java.util.Map;

/**
 * 宽睿 OES 只读查询门面（M2：资金/持仓/委托/成交对账）。
 * <p>
 * 默认 Noop；真实客户端仅在 Maven {@code -Pkuangrui} 且
 * {@code quant.kuangrui.enabled}+{@code oes.enabled} 时装配。不下单（报撤见 M3）。
 * </p>
 */
public interface OesReadonlyService {

    /** 是否具备真实 OES 只读能力（jar + 开关）。 */
    boolean isLive();

    /** 状态摘要（供运维 API）。 */
    Map<String, Object> status();

    /**
     * 确保已登录并完成 {@code sendRptSync}。
     *
     * @return true 已就绪
     */
    boolean ensureReady();

    /** 柜台资金（金额已÷10000 为元）。 */
    List<Map<String, Object>> queryCash();

    /** 柜台持仓（数量为股；成本价为元）。 */
    List<Map<String, Object>> queryHoldings();

    /** 柜台委托（价额为元）。 */
    List<Map<String, Object>> queryOrders();

    /** 柜台成交（价额为元）。 */
    List<Map<String, Object>> queryTrades();

    /**
     * 一次拉取资金+持仓+委托+成交快照，供对账。
     */
    Map<String, Object> snapshot();

    /** 关闭客户端连接。 */
    void stop();
}
