package com.quant.stock.task;

import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.pool.TradePoolService;
import com.quant.stock.trade.TradeGatewayService;
import com.quant.stock.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 预置定时任务业务处理器（与 {@link StrategyTask} 并列）。
 * <p>
 * 本地可跑通的路径已实现；第三方行情/券商 API 对接点以 {@code TODO(api)} 标注。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class ScheduleJobHandlers {

    /** 日线滞后超过该天数则告警（含周末，粗判） */
    private static final int DAILY_STALE_DAYS = 5;
    /** 分钟线滞后超过该小时数则告警 */
    private static final int MINUTE_STALE_HOURS = 48;

    private final MarketDataService marketDataService;
    private final TradeGatewayService tradeGatewayService;
    private final TradePoolService tradePoolService;
    private final RedisLockUtil redisLockUtil;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 行情采集：按股票池刷新本地 K 线缓存/落库。
     * <p>
     * TODO(api): 接入真实行情源（扩展 {@code KlineSdkClient}：日线/分钟增量拉取、复权、停牌标记），
     * 当前走 {@link MarketDataService#fetchAndPersist1Min}（db/mock/sdk-noop 回退）。
     */
    public void marketCollect() {
        if (!redisLockUtil.tryLock("job:market-collect", 55)) {
            return;
        }
        List<String> codes = new ArrayList<String>();
        for (Map<String, String> u : tradePoolService.listUniverse()) {
            codes.add(u.get("code"));
        }
        if (codes.isEmpty()) {
            log.warn("[market-collect] 全市场为空，跳过");
            return;
        }
        // TODO(api): 按交易日历/交易时段决定是否拉取；非交易时段可只补历史缺口
        int ok = 0;
        int fail = 0;
        for (String code : codes) {
            try {
                List<BarDTO> bars = marketDataService.fetchAndPersist1Min(code);
                if (bars == null || bars.isEmpty()) {
                    fail++;
                    log.warn("[market-collect] 无数据 code={}", code);
                } else {
                    ok++;
                    BarDTO last = bars.get(bars.size() - 1);
                    log.debug("[market-collect] code={} bars={} last={}",
                            code, bars.size(), last.getBarBegin());
                }
            } catch (Exception e) {
                fail++;
                log.warn("[market-collect] 失败 code={}: {}", code, e.getMessage());
            }
        }
        log.info("[market-collect] 完成 ok={} fail={} universe={}", ok, fail, codes.size());
    }

    /**
     * 持仓盈亏同步：用本地持仓 + 最新价估算市值/浮动概况并打日志。
     * <p>
     * TODO(api): 对接券商/柜台持仓与成本（含可用/冻结、今买今卖）；真实盈亏应以成交回报对账为准。
     */
    public void positionPnlSync() {
        if (!redisLockUtil.tryLock("job:position-pnl-sync", 50)) {
            return;
        }
        // TODO(api): 先 pull 远程持仓覆盖本地账本，再算盈亏
        Map<String, Integer> positions = tradeGatewayService.queryPositions();
        if (positions == null || positions.isEmpty()) {
            log.info("[position-pnl-sync] 当前无持仓");
            return;
        }
        BigDecimal totalMv = BigDecimal.ZERO;
        int names = 0;
        for (Map.Entry<String, Integer> e : positions.entrySet()) {
            Integer shares = e.getValue();
            if (shares == null || shares <= 0) {
                continue;
            }
            String code = e.getKey();
            BigDecimal px = lastPrice(code);
            BigDecimal mv = px.multiply(BigDecimal.valueOf(shares));
            totalMv = totalMv.add(mv);
            names++;
            // TODO(api): 用券商成本价计算浮动盈亏 = (现价-成本)*股数 - 预估费用
            log.info("[position-pnl-sync] {} x{} mark={} mv={} (成本/浮动待对接券商API)",
                    code, shares, px.toPlainString(), mv.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        log.info("[position-pnl-sync] 持仓只数={} 市值合计≈{}",
                names, totalMv.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    /**
     * 全市场扫描：筛可入选标的并覆盖唯一目标池（无人工确认）。
     * <p>
     * TODO(api): 全市场行情源就绪后，universe 来自外部列表；筛选因子可接更多 API。
     */
    public void poolRebuild() {
        if (!redisLockUtil.tryLock("job:pool-rebuild", 600)) {
            return;
        }
        tradePoolService.rebuildFromUniverse();
    }

    /**
     * 数据校验：检查股票池日线/分钟是否为空或明显滞后。
     * <p>
     * TODO(api): 与外部行情源对账（条数、OHLC 抽样、复权因子一致性）。
     */
    public void dataValidate() {
        if (!redisLockUtil.tryLock("job:data-validate", 120)) {
            return;
        }
        List<String> codes = new ArrayList<String>();
        for (Map<String, String> u : tradePoolService.listUniverse()) {
            codes.add(u.get("code"));
        }
        if (codes.isEmpty()) {
            log.warn("[data-validate] 全市场为空，跳过");
            return;
        }
        int warn = 0;
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        for (String code : codes) {
            try {
                Integer dailyCnt = jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM market_daily WHERE symbol = ?", Integer.class, code);
                LocalDate maxDaily = jdbcTemplate.query(
                        "SELECT MAX(trade_date) FROM market_daily WHERE symbol = ?",
                        rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null,
                        code);
                Integer minuteCnt = jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM market_minute WHERE symbol = ?", Integer.class, code);
                LocalDateTime maxMinute = jdbcTemplate.query(
                        "SELECT MAX(trade_time) FROM market_minute WHERE symbol = ?",
                        rs -> rs.next() ? rs.getObject(1, LocalDateTime.class) : null,
                        code);

                boolean bad = false;
                if (dailyCnt == null || dailyCnt <= 0 || maxDaily == null) {
                    bad = true;
                    log.warn("[data-validate] {} 日线为空", code);
                } else {
                    long lagDays = ChronoUnit.DAYS.between(maxDaily, today);
                    if (lagDays > DAILY_STALE_DAYS) {
                        bad = true;
                        log.warn("[data-validate] {} 日线滞后 {} 天 (last={})", code, lagDays, maxDaily);
                    }
                }
                if (minuteCnt == null || minuteCnt <= 0 || maxMinute == null) {
                    bad = true;
                    log.warn("[data-validate] {} 分钟线为空", code);
                } else {
                    long lagHours = ChronoUnit.HOURS.between(maxMinute, now);
                    if (lagHours > MINUTE_STALE_HOURS) {
                        bad = true;
                        log.warn("[data-validate] {} 分钟线滞后 {} 小时 (last={})",
                                code, lagHours, maxMinute);
                    }
                }
                if (bad) {
                    warn++;
                } else {
                    log.debug("[data-validate] {} ok daily={}@{} minute={}@{}",
                            code, dailyCnt, maxDaily, minuteCnt, maxMinute);
                }
                // TODO(api): 抽样对比外部 API 最新 OHLC / 成交量
            } catch (Exception e) {
                warn++;
                log.warn("[data-validate] {} 校验异常: {}", code, e.getMessage());
            }
        }
        log.info("[data-validate] 完成 universe={} warn={}", codes.size(), warn);
    }

    private BigDecimal lastPrice(String code) {
        try {
            List<BarDTO> bars = marketDataService.loadMinuteBars(code);
            if (bars != null && !bars.isEmpty() && bars.get(bars.size() - 1).getClose() != null) {
                return bars.get(bars.size() - 1).getClose();
            }
        } catch (Exception ignored) {
        }
        return BigDecimal.ZERO;
    }
}
