package com.quant.stock.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 表 {@code bt_backtest_record} 行映射：单股/组合回测历史持久化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BtBacktestRecordDO {
    private Long id;
    /** 业务主键 UUID */
    private String recordId;
    /** SINGLE / PORTFOLIO */
    private String kind;
    /** 保存时间 */
    private LocalDateTime savedAt;
    /** 单股代码；组合为空 */
    private String stockCode;
    /** 组合成分股 JSON 数组 */
    private String stockCodesJson;
    /** K 线周期标识 */
    private String period;
    private String backStart;
    private String backEnd;
    private BigDecimal initCapital;
    private BigDecimal finalAsset;
    private BigDecimal totalRate;
    private BigDecimal maxDrawdown;
    private Integer totalTradeNum;
    private BigDecimal winRate;
    /** {@link BackTestTradeStats} JSON */
    private String tradeStatsJson;
    /** 成交流水 JSON */
    private String tradesJson;
    /** 组合分股结果 JSON */
    private String stockResultsJson;
    /** 策略相关配置指纹（P0-93） */
    private String configFingerprint;
}
