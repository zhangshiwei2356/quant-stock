package com.quant.stock.kuangrui;

import java.util.List;
import java.util.Map;

/**
 * 宽睿 MDS → {@code market_1min(data_source=MDS)} 摄入门面。
 * <p>
 * 默认实现为 Noop；真实客户端仅在 Maven {@code -Pkuangrui} 且开关打开时装配。
 * </p>
 */
public interface MdsMinuteIngestService {

    /** 是否具备真实 MDS 能力（jar + 开关 + 配置就绪）。 */
    boolean isLive();

    /** 状态摘要（供运维 API）。 */
    Map<String, Object> status();

    /**
     * 查询通道拉快照，更新分钟桶并落库。
     *
     * @return 成功 upsert 的 bar 条数
     */
    int pullAndPersist(List<String> codes);

    /**
     * 启动 TCP 订阅（L1）；回调异步入桶，由 {@link #flushBuckets()} / 定时任务落库。
     *
     * @return true 已订阅或已在订阅中
     */
    boolean startSubscribe(List<String> codes);

    /** 停止订阅并关闭客户端。 */
    void stopSubscribe();

    /** 将已闭合/当前分钟桶刷入 {@code market_1min}。 */
    int flushBuckets();
}
