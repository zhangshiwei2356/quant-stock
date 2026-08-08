package com.quant.stock.admin;

import com.quant.stock.market.MarketDataSources;
import com.quant.stock.pool.TradePoolService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 抽样对账 {@code market_1min} 中 TDX 与 MDS 两源的条数、最新时间与重叠 bar 收盘价偏差（bp）。
 * <p>
 * 优先抽目标池，不足再用库中有 MDS/TDX 标记的标的补齐。只读，不改库。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "quant", name = "db-enabled", havingValue = "true")
public class MarketSourceSampleReconcileService {

    /** 重叠 bar 收盘价偏差告警阈值（基点） */
    static final int CLOSE_BP_THRESHOLD = 50;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;
    private static final int OVERLAP_SAMPLE_CAP = 500;
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final TradePoolService tradePoolService;

    public MarketSourceSampleReconcileService(JdbcTemplate jdbc, TradePoolService tradePoolService) {
        this.jdbc = jdbc;
        this.tradePoolService = tradePoolService;
    }

    /**
     * 抽样对账。
     *
     * @param limit 最多检查只数，默认 20，上限 200
     */
    public Map<String, Object> sample(Integer limit) {
        int lim = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<String> sampleCodes = pickSampleCodes(lim);

        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int bothPresent = 0;
        int missingTdx = 0;
        int missingMds = 0;
        int closeDiffWarn = 0;
        int maxCloseDiffBpSeen = 0;

        for (String code : sampleCodes) {
            Map<String, Object> row = compareOne(code);
            items.add(row);
            boolean hasTdx = Boolean.TRUE.equals(row.get("hasTdx"));
            boolean hasMds = Boolean.TRUE.equals(row.get("hasMds"));
            if (hasTdx && hasMds) {
                bothPresent++;
            } else if (!hasTdx && hasMds) {
                missingTdx++;
            } else if (hasTdx) {
                missingMds++;
            }
            if (Boolean.TRUE.equals(row.get("closeDiffWarn"))) {
                closeDiffWarn++;
            }
            Object bp = row.get("maxCloseDiffBp");
            if (bp instanceof Number) {
                maxCloseDiffBpSeen = Math.max(maxCloseDiffBpSeen, ((Number) bp).intValue());
            }
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("asOf", LocalDateTime.now().format(DT_FMT));
        out.put("limit", lim);
        out.put("sampled", sampleCodes.size());
        out.put("closeBpThreshold", CLOSE_BP_THRESHOLD);
        out.put("bothPresent", bothPresent);
        out.put("missingTdx", missingTdx);
        out.put("missingMds", missingMds);
        out.put("closeDiffWarnCount", closeDiffWarn);
        out.put("maxCloseDiffBp", maxCloseDiffBpSeen);
        out.put("tdxSource", MarketDataSources.TDX);
        out.put("mdsSource", MarketDataSources.MDS);
        out.put("items", items);
        out.put("hint", "抽样优先目标池，再补库中有 MDS/TDX 的标的；"
                + "比较 market_1min 两源条数、最新时间与重叠 bar 收盘价偏差（阈 "
                + CLOSE_BP_THRESHOLD + " bp）。只读不改库。");
        return out;
    }

    /** 目标池优先，再用 MDS、TDX 标的补齐至 limit。 */
    private List<String> pickSampleCodes(int limit) {
        LinkedHashSet<String> ordered = new LinkedHashSet<String>();
        try {
            for (String c : tradePoolService.listActiveCodes()) {
                if (c != null && !c.isEmpty()) {
                    ordered.add(c);
                    if (ordered.size() >= limit) {
                        return new ArrayList<String>(ordered);
                    }
                }
            }
        } catch (Exception e) {
            log.error("抽样对账读取目标池失败: {}", e.getMessage(), e);
        }
        for (String src : new String[]{MarketDataSources.MDS, MarketDataSources.TDX}) {
            try {
                List<String> syms = jdbc.queryForList(
                        "SELECT DISTINCT symbol FROM market_1min WHERE data_source = ? ORDER BY symbol",
                        String.class, src);
                for (String s : syms) {
                    if (s == null || s.isEmpty()) {
                        continue;
                    }
                    ordered.add(s);
                    if (ordered.size() >= limit) {
                        return new ArrayList<String>(ordered);
                    }
                }
            } catch (Exception e) {
                log.error("抽样对账读取 {} 标的失败: {}", src, e.getMessage(), e);
            }
        }
        return new ArrayList<String>(ordered);
    }

    private Map<String, Object> compareOne(String code) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", code);
        SourceStats tdx = loadSourceStats(code, MarketDataSources.TDX);
        SourceStats mds = loadSourceStats(code, MarketDataSources.MDS);
        m.put("hasTdx", tdx.count > 0);
        m.put("hasMds", mds.count > 0);
        m.put("tdxCount", tdx.count);
        m.put("mdsCount", mds.count);
        m.put("tdxMaxTime", tdx.maxTime == null ? null : tdx.maxTime.format(DT_FMT));
        m.put("mdsMaxTime", mds.maxTime == null ? null : mds.maxTime.format(DT_FMT));

        List<String> issues = new ArrayList<String>();
        if (tdx.count <= 0) {
            issues.add("无 TDX 分钟");
        }
        if (mds.count <= 0) {
            issues.add("无 MDS 分钟");
        }

        int overlap = 0;
        int maxBp = 0;
        int warnBars = 0;
        if (tdx.count > 0 && mds.count > 0) {
            OverlapStats ov = loadOverlap(code);
            overlap = ov.overlapCount;
            maxBp = ov.maxCloseDiffBp;
            warnBars = ov.warnBars;
            m.put("overlapCount", overlap);
            m.put("overlapSampled", ov.sampled);
            m.put("maxCloseDiffBp", maxBp);
            m.put("avgCloseDiffBp", ov.avgCloseDiffBp);
            m.put("closeDiffWarnBars", warnBars);
            if (overlap <= 0) {
                issues.add("两源无重叠 bar");
            } else if (maxBp > CLOSE_BP_THRESHOLD) {
                issues.add("重叠收盘偏差最大 " + maxBp + " bp（>" + CLOSE_BP_THRESHOLD + "）");
            }
        } else {
            m.put("overlapCount", 0);
            m.put("maxCloseDiffBp", 0);
        }
        boolean closeDiffWarn = maxBp > CLOSE_BP_THRESHOLD;
        m.put("closeDiffWarn", closeDiffWarn);
        m.put("ok", issues.isEmpty());
        m.put("issues", issues);
        m.put("issueText", issues.isEmpty() ? "一致" : String.join("；", issues));
        return m;
    }

    private SourceStats loadSourceStats(String code, String source) {
        SourceStats s = new SourceStats();
        try {
            jdbc.query(
                    "SELECT COUNT(1) cnt, MAX(trade_time) mx FROM market_1min"
                            + " WHERE symbol = ? AND data_source = ?",
                    new Object[]{code, source},
                    rs -> {
                        if (rs.next()) {
                            s.count = rs.getInt(1);
                            java.sql.Timestamp mx = rs.getTimestamp(2);
                            if (mx != null) {
                                s.maxTime = mx.toLocalDateTime();
                            }
                        }
                        return null;
                    });
        } catch (Exception e) {
            log.error("抽样对账统计 {} {} 失败: {}", code, source, e.getMessage(), e);
        }
        return s;
    }

    private OverlapStats loadOverlap(String code) {
        OverlapStats ov = new OverlapStats();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT a.close AS tdx_close, b.close AS mds_close "
                            + "FROM market_1min a "
                            + "INNER JOIN market_1min b ON a.symbol = b.symbol AND a.trade_time = b.trade_time "
                            + "WHERE a.symbol = ? AND a.data_source = ? AND b.data_source = ? "
                            + "ORDER BY a.trade_time DESC LIMIT " + OVERLAP_SAMPLE_CAP,
                    code, MarketDataSources.TDX, MarketDataSources.MDS);
            long sumBp = 0L;
            int n = 0;
            int maxBp = 0;
            int warn = 0;
            for (Map<String, Object> r : rows) {
                BigDecimal tdxClose = toBd(r.get("tdx_close"));
                BigDecimal mdsClose = toBd(r.get("mds_close"));
                if (tdxClose == null || mdsClose == null
                        || tdxClose.compareTo(BigDecimal.ZERO) <= 0
                        || mdsClose.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                n++;
                BigDecimal mid = tdxClose.add(mdsClose)
                        .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
                if (mid.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                int bp = tdxClose.subtract(mdsClose).abs()
                        .multiply(BigDecimal.valueOf(10000))
                        .divide(mid, 0, RoundingMode.HALF_UP)
                        .intValue();
                sumBp += bp;
                if (bp > maxBp) {
                    maxBp = bp;
                }
                if (bp > CLOSE_BP_THRESHOLD) {
                    warn++;
                }
            }
            // 总重叠数（不截断）
            Integer totalOverlap = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM market_1min a "
                            + "INNER JOIN market_1min b ON a.symbol = b.symbol AND a.trade_time = b.trade_time "
                            + "WHERE a.symbol = ? AND a.data_source = ? AND b.data_source = ?",
                    Integer.class, code, MarketDataSources.TDX, MarketDataSources.MDS);
            ov.overlapCount = totalOverlap == null ? n : totalOverlap;
            ov.sampled = n;
            ov.maxCloseDiffBp = maxBp;
            ov.warnBars = warn;
            ov.avgCloseDiffBp = n <= 0 ? 0 : (int) Math.round(sumBp * 1.0 / n);
        } catch (Exception e) {
            log.error("抽样对账重叠比较 {} 失败: {}", code, e.getMessage(), e);
        }
        return ov;
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (Exception e) {
            log.error("行情抽样对账异常", e);
            return null;
        }
    }

    private static final class SourceStats {
        int count;
        LocalDateTime maxTime;
    }

    private static final class OverlapStats {
        int overlapCount;
        int sampled;
        int maxCloseDiffBp;
        int avgCloseDiffBp;
        int warnBars;
    }
}
