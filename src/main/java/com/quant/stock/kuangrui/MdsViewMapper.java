package com.quant.stock.kuangrui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MDS 静态/状态查询 → 业务视图（价÷10000，代码补齐 6 位）。不依赖 quant360 jar。
 */
public final class MdsViewMapper {

    private MdsViewMapper() {
    }

    /** 证券静态：涨跌停/昨收为元；股本为股。 */
    public static Map<String, Object> stockStatic(String securityId,
                                                  String securityName,
                                                  long upperLimitMilli,
                                                  long lowerLimitMilli,
                                                  long prevCloseMilli,
                                                  long outstandingShare,
                                                  long publicFloatShare,
                                                  int suspendFlag,
                                                  int securityStatus) {
        // 与 OES stock 视图字段对齐，便于门面合并
        return OesViewMapper.stock(securityId, securityName, upperLimitMilli, lowerLimitMilli,
                prevCloseMilli, outstandingShare, publicFloatShare, suspendFlag, securityStatus);
    }

    /** 证券实时状态。 */
    public static Map<String, Object> securityStatus(String securityId,
                                                     int suspendFlag,
                                                     int securityStatus,
                                                     int tradingPhase) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", OesViewMapper.normalizeCode(securityId));
        m.put("suspendFlag", suspendFlag);
        m.put("securityStatus", securityStatus);
        m.put("tradingPhase", tradingPhase);
        m.put("suspended", OesViewMapper.isSuspendedFlag(suspendFlag, securityStatus));
        return m;
    }

    /** 交易时段状态。 */
    public static Map<String, Object> trdSession(int mktId, int sessionType, int sessionStatus) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("mktId", mktId);
        m.put("sessionType", sessionType);
        m.put("sessionStatus", sessionStatus);
        // 常见：1=开市/交易中；0=未开/休市（资料包枚举因版本略异，运维以原始值为准）
        m.put("open", sessionStatus == 1 || sessionStatus == 2);
        return m;
    }

    /** 流通股本（股）→ 亿股。 */
    public static BigDecimal sharesToYi(long shares) {
        if (shares <= 0L) {
            return null;
        }
        return BigDecimal.valueOf(shares).divide(new BigDecimal("100000000"), 4, RoundingMode.HALF_UP);
    }
}
