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

    /**
     * M4：证券产品信息（涨跌停价/停牌/股本等，价÷10000 为元）。
     * {@code code} 为空则尽量拉全量（视柜台过滤支持）。
     */
    List<Map<String, Object>> queryStock(String code);

    /** M4：柜台当前交易日（YYYY-MM-DD 字符串；失败空）。 */
    Map<String, Object> queryTradingDay();

    /** M4：佣金费率列表（费率已换算为小数，如 0.0003）。 */
    List<Map<String, Object>> queryCommissionRate();

    /** 关闭客户端连接。 */
    void stop();
}
