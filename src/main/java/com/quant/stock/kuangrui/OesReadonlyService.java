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

    /**
     * M5+：客户端总览（{@code queryClientOverview}）。
     * 含客户/资金账户/股东账户摘要；失败返回空 Map（含 {@code ok=false}）。
     */
    Map<String, Object> queryClientOverview();

    /**
     * M5+：股东账户列表（{@code queryInvAcct}）。
     */
    List<Map<String, Object>> queryInvAcct();

    /**
     * M5+：主柜资金（{@code queryCounterCash}）。
     * {@code cashAcctId} 可空（空则不过滤）。
     */
    List<Map<String, Object>> queryCounterCash(String cashAcctId);

    /**
     * M5+：最大可买卖数量（{@code queryMaxTradableQty}）。
     * {@code side} 为 BUY/SELL；{@code priceYuan} 限价（元）。
     */
    Map<String, Object> queryMaxTradableQty(String code, String side, java.math.BigDecimal priceYuan);

    /**
     * M6：银证/出入金流水（{@code queryCashTransferSerial}）。
     * {@code cashAcctId} 可空。
     */
    List<Map<String, Object>> queryCashTransferSerial(String cashAcctId);

    /** 关闭客户端连接。 */
    void stop();

    /**
     * 用指定账号短登录验柜后关闭（不写库、不占用长期会话）。
     * 默认 noop；真实实现仅 {@code -Pkuangrui}。
     */
    Map<String, Object> probeLogon(String username, String password);
}
