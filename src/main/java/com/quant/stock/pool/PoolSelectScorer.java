package com.quant.stock.pool;

import com.quant.stock.backtest.dto.BatchScanResultDTO;
import com.quant.stock.config.QuantProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 目标池多因子打分（趋势为主 + 动量/波动/流动性），总分约 100。
 * 不含财务/板块（数据未接入）。
 */
@Component
public class PoolSelectScorer {

    private final QuantProperties props;

    public PoolSelectScorer(QuantProperties props) {
        this.props = props;
    }

    /**
     * 对本批扫描结果计算横截面动量百分位并写入 {@link BatchScanResultDTO#poolScore}。
     */
    public void applyScores(List<BatchScanResultDTO> scanned) {
        if (scanned == null || scanned.isEmpty()) {
            return;
        }
        fillMomPercentiles(scanned);
        for (BatchScanResultDTO r : scanned) {
            scoreOne(r);
        }
    }

    /** 入池门槛：综合分 ≥ 配置，且至少具备多头雏形（MA5&gt;MA20 或当日金叉可买） */
    public boolean isEligible(BatchScanResultDTO r) {
        if (r == null || r.getPoolScore() == null) {
            return false;
        }
        BigDecimal min = props.getPoolScoreMin() == null ? new BigDecimal("45") : props.getPoolScoreMin();
        if (r.getPoolScore().compareTo(min) < 0) {
            return false;
        }
        if (Boolean.TRUE.equals(r.getCanBuyNow())) {
            return true;
        }
        if (r.getMa5() != null && r.getMa20() != null && r.getMa5().compareTo(r.getMa20()) > 0) {
            BigDecimal rsiMax = props.getRsiBuyMax();
            if (r.getRsi14() != null && rsiMax != null && r.getRsi14().compareTo(rsiMax) > 0) {
                return false;
            }
            return true;
        }
        return false;
    }

    public List<BatchScanResultDTO> pickTop(List<BatchScanResultDTO> scanned, int max) {
        applyScores(scanned);
        List<BatchScanResultDTO> eligible = new ArrayList<BatchScanResultDTO>();
        for (BatchScanResultDTO r : scanned) {
            if (isEligible(r)) {
                eligible.add(r);
            }
        }
        Collections.sort(eligible, new Comparator<BatchScanResultDTO>() {
            @Override
            public int compare(BatchScanResultDTO a, BatchScanResultDTO b) {
                BigDecimal sa = a.getPoolScore() == null ? BigDecimal.ZERO : a.getPoolScore();
                BigDecimal sb = b.getPoolScore() == null ? BigDecimal.ZERO : b.getPoolScore();
                int c = sb.compareTo(sa);
                if (c != 0) {
                    return c;
                }
                BigDecimal ra = a.getTotalRate() == null ? BigDecimal.ZERO : a.getTotalRate();
                BigDecimal rb = b.getTotalRate() == null ? BigDecimal.ZERO : b.getTotalRate();
                return rb.compareTo(ra);
            }
        });
        if (eligible.size() > max) {
            return new ArrayList<BatchScanResultDTO>(eligible.subList(0, max));
        }
        return eligible;
    }

    void scoreOne(BatchScanResultDTO r) {
        int trend = scoreTrend(r);
        int mom = scoreMomentum(r);
        int vol = scoreVolatility(r);
        int liq = scoreLiquidity(r);
        int total = trend + mom + vol + liq;
        r.setPoolScore(BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP));
        r.setScoreTag(buildTag(r, trend, mom, vol, liq));
    }

    private int scoreTrend(BatchScanResultDTO r) {
        int s = 0;
        // 均线排列（最高 15）
        if (isFullBull(r)) {
            s += 15;
        } else if (isPartialBull(r)) {
            s += 10;
        } else if (r.getMa5() != null && r.getMa20() != null && r.getMa5().compareTo(r.getMa20()) > 0) {
            s += 8;
        } else if (Boolean.TRUE.equals(r.getCanBuyNow())) {
            s += 8;
        }
        // MA60 斜率（10）
        if (Boolean.TRUE.equals(r.getMa60SlopeUp())) {
            s += 10;
        } else if (r.getMa60() != null && r.getLastClose() != null
                && r.getLastClose().compareTo(r.getMa60()) >= 0) {
            s += 5;
        }
        // ADX（5）
        if (r.getAdx14() != null) {
            double adx = r.getAdx14().doubleValue();
            if (adx >= 30) {
                s += 5;
            } else if (adx >= 20) {
                s += 3;
            }
        }
        return s;
    }

    private int scoreMomentum(BatchScanResultDTO r) {
        int s = 0;
        // 近 20 日横截面排名（15）
        if (r.getMom20Percentile() != null) {
            double p = r.getMom20Percentile().doubleValue();
            if (p >= 0.90) {
                s += 15;
            } else if (p >= 0.70) {
                s += 8;
            }
        }
        // 近 5 日温和上涨（10）；暴涨不得分
        if (r.getMom5() != null) {
            double m = r.getMom5().doubleValue();
            if (m > 0.15) {
                s += 0;
            } else if (m >= 0.03 && m <= 0.08) {
                s += 10;
            } else if (m > 0 && m < 0.03) {
                s += 5;
            } else if (m > 0.08 && m <= 0.15) {
                s += 5;
            }
        }
        return s;
    }

    private int scoreVolatility(BatchScanResultDTO r) {
        if (r.getAtr14() == null || r.getLastClose() == null
                || r.getLastClose().compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        double atrPct = r.getAtr14().divide(r.getLastClose(), 6, RoundingMode.HALF_UP).doubleValue();
        // 适中波动 1%~4%
        if (atrPct >= 0.01 && atrPct <= 0.04) {
            return 15;
        }
        if (atrPct > 0.004 && atrPct < 0.06) {
            return 8;
        }
        return 0;
    }

    private int scoreLiquidity(BatchScanResultDTO r) {
        if (r.getAvgAmount20() == null) {
            return 5;
        }
        double amt = r.getAvgAmount20().doubleValue();
        // 约 1 亿 / 5000 万
        if (amt >= 100_000_000d) {
            return 10;
        }
        if (amt >= 50_000_000d) {
            return 8;
        }
        if (amt >= 20_000_000d) {
            return 5;
        }
        return 0;
    }

    private static boolean isFullBull(BatchScanResultDTO r) {
        return gt(r.getMa5(), r.getMa10()) && gt(r.getMa10(), r.getMa20()) && gt(r.getMa20(), r.getMa60());
    }

    private static boolean isPartialBull(BatchScanResultDTO r) {
        return gt(r.getMa5(), r.getMa20()) && gt(r.getMa20(), r.getMa60());
    }

    private static boolean gt(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) > 0;
    }

    private String buildTag(BatchScanResultDTO r, int trend, int mom, int vol, int liq) {
        StringBuilder sb = new StringBuilder();
        if (isFullBull(r)) {
            sb.append("完美多头");
        } else if (isPartialBull(r)) {
            sb.append("多头排列");
        } else if (Boolean.TRUE.equals(r.getCanBuyNow())) {
            sb.append("金叉可买");
        } else if (gt(r.getMa5(), r.getMa20())) {
            sb.append("MA5>MA20");
        } else {
            sb.append("弱趋势");
        }
        if (Boolean.TRUE.equals(r.getMa60SlopeUp())) {
            sb.append("+MA60上行");
        }
        if (r.getMom20Percentile() != null && r.getMom20Percentile().doubleValue() >= 0.90) {
            sb.append("+强动量");
        }
        sb.append(" T").append(trend).append("/M").append(mom).append("/V").append(vol).append("/L").append(liq);
        return sb.toString();
    }

    private static void fillMomPercentiles(List<BatchScanResultDTO> scanned) {
        List<BatchScanResultDTO> withMom = new ArrayList<BatchScanResultDTO>();
        for (BatchScanResultDTO r : scanned) {
            if (r != null && r.getMom20() != null) {
                withMom.add(r);
            }
        }
        if (withMom.isEmpty()) {
            return;
        }
        Collections.sort(withMom, new Comparator<BatchScanResultDTO>() {
            @Override
            public int compare(BatchScanResultDTO a, BatchScanResultDTO b) {
                return a.getMom20().compareTo(b.getMom20());
            }
        });
        int n = withMom.size();
        for (int i = 0; i < n; i++) {
            // 最低 0，最高接近 1
            double pct = n == 1 ? 1.0 : (double) i / (double) (n - 1);
            withMom.get(i).setMom20Percentile(BigDecimal.valueOf(pct).setScale(4, RoundingMode.HALF_UP));
        }
    }
}
