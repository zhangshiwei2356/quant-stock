package com.quant.stock.task;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.mapper.ScheduleJobMapper;
import com.quant.stock.market.TdxScriptBackfillService;
import com.quant.stock.task.dto.ScheduleJobDO;
import com.quant.stock.task.dto.ScheduleJobUpdateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 MySQL {@code sys_schedule_job} 的动态调度：启停与 cron 热更新，无需改 yml 重启。
 * <p>
 * {@code quant.schedule.enabled=false} 为全局总闸（不注册任何触发器，库表仍可编辑）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class DynamicScheduleService implements ApplicationRunner {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 手动「执行一次」改后台跑，避免 HTTP 长时间无响应、页面无进度 */
    private static final Set<String> ASYNC_MANUAL_JOBS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "day-collect",
            "pool-minute-backfill",
            "pool-rebuild",
            "factor-daily-rebuild",
            "after-market-batch-scan",
            "data-validate",
            "market-collect"
    )));

    private final ScheduleJobMapper scheduleJobMapper;
    private final StrategyTask strategyTask;
    private final ScheduleJobHandlers scheduleJobHandlers;
    private final QuantProperties quantProperties;
    private final JdbcTemplate jdbcTemplate;
    private final TdxScriptBackfillService tdxScriptBackfillService;

    private final ThreadPoolTaskScheduler taskScheduler = createScheduler();
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<String, ScheduledFuture<?>>();
    private final AtomicReference<ManualRunState> manualRunRef = new AtomicReference<ManualRunState>();
    private final ExecutorService manualRunExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "schedule-manual-run");
        t.setDaemon(true);
        return t;
    });

    private static ThreadPoolTaskScheduler createScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(4);
        s.setThreadNamePrefix("quant-job-");
        s.setRemoveOnCancelPolicy(true);
        s.initialize();
        return s;
    }

    /** Spring Boot 启动完成后建表、种子数据并注册调度。 */
    @Override
    public void run(ApplicationArguments args) {
        ensureSchemaAndSeed();
        reloadAll();
    }

    /** 销毁时取消全部已注册触发器并关闭调度线程池。 */
    @PreDestroy
    public void destroy() {
        cancelAll();
        taskScheduler.shutdown();
        manualRunExecutor.shutdownNow();
    }

    /** 取消并重新从库表加载全部启用任务的触发器。 */
    public synchronized void reloadAll() {
        cancelAll();
        if (!quantProperties.getSchedule().isEnabled()) {
            log.info("quant.schedule.enabled=false，跳过注册定时任务（页面仍可改库表）");
            return;
        }
        List<ScheduleJobDO> jobs = scheduleJobMapper.selectAll();
        int n = 0;
        for (ScheduleJobDO job : jobs) {
            if (job.getEnabled() != null && job.getEnabled() == 1) {
                try {
                    scheduleOne(job);
                    n++;
                } catch (Exception e) {
                    log.error("注册任务失败 {}: {}", job.getJobCode(), e.getMessage());
                }
            }
        }
        log.info("动态调度已加载：启用 {} / 共 {}", n, jobs.size());
    }

    /** 任务列表视图（含总闸、是否已注册、预置说明）。 */
    public List<Map<String, Object>> listJobs() {
        boolean masterOn = quantProperties.getSchedule().isEnabled();
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (ScheduleJobDO job : scheduleJobMapper.selectAll()) {
            list.add(toView(job, masterOn));
        }
        return list;
    }

    /** 调度总览：总闸状态、已注册数、任务明细。 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        boolean masterOn = quantProperties.getSchedule().isEnabled();
        m.put("enabled", masterOn);
        m.put("schedulerActive", masterOn);
        m.put("registeredCount", futures.size());
        m.put("hint", masterOn
                ? "动态调度已开启；各任务以库表 enabled 为准，改后立即生效"
                : "总闸 quant.schedule.enabled=false；库表可编辑，开启总闸并重启后才会真正调度");
        m.put("jobs", listJobs());
        m.put("manualRun", snapshotManualRun());
        m.put("tdxScript", tdxScriptBackfillService.status());
        return m;
    }

    /**
     * 部分更新任务配置并热重载调度。
     *
     * @return 更新后的单条任务视图
     */
    public Map<String, Object> updateJob(String jobCode, ScheduleJobUpdateRequest req) {
        ScheduleJobDO existing = requireJob(jobCode);
        ScheduleJobDO patch = ScheduleJobDO.builder().jobCode(jobCode).build();
        if (req.getJobName() != null) {
            patch.setJobName(req.getJobName().trim());
        }
        if (req.getRemark() != null) {
            patch.setRemark(req.getRemark());
        }
        if (req.getEnabled() != null) {
            patch.setEnabled(Boolean.TRUE.equals(req.getEnabled()) ? 1 : 0);
        }
        String triggerType = req.getTriggerType() != null
                ? req.getTriggerType().trim().toUpperCase()
                : existing.getTriggerType();
        if (req.getTriggerType() != null) {
            if (!"CRON".equals(triggerType) && !"FIXED_RATE".equals(triggerType)) {
                throw new IllegalArgumentException("triggerType 仅支持 CRON 或 FIXED_RATE");
            }
            patch.setTriggerType(triggerType);
        }
        if (req.getCronExpr() != null) {
            patch.setCronExpr(req.getCronExpr().trim());
        }
        if (req.getIntervalMs() != null) {
            patch.setIntervalMs(req.getIntervalMs());
        }

        ScheduleJobDO merged = ScheduleJobDO.builder()
                .jobCode(existing.getJobCode())
                .triggerType(triggerType)
                .cronExpr(req.getCronExpr() != null ? req.getCronExpr().trim() : existing.getCronExpr())
                .intervalMs(req.getIntervalMs() != null ? req.getIntervalMs() : existing.getIntervalMs())
                .build();
        validateTrigger(merged);

        scheduleJobMapper.updateByCode(patch);
        if (req.getEnabled() != null && Boolean.TRUE.equals(req.getEnabled())) {
            disablePeerPoolJob(jobCode);
        }
        reloadAll();
        return toView(requireJob(jobCode), quantProperties.getSchedule().isEnabled());
    }

    /**
     * 启停单条任务；{@code enabled} 为 null 时在 0/1 间切换。
     */
    public Map<String, Object> toggle(String jobCode, Boolean enabled) {
        ScheduleJobDO existing = requireJob(jobCode);
        int next;
        if (enabled != null) {
            next = Boolean.TRUE.equals(enabled) ? 1 : 0;
        } else {
            next = (existing.getEnabled() != null && existing.getEnabled() == 1) ? 0 : 1;
        }
        scheduleJobMapper.updateEnabled(jobCode, next);
        if (next == 1) {
            disablePeerPoolJob(jobCode);
        }
        reloadAll();
        return toView(requireJob(jobCode), quantProperties.getSchedule().isEnabled());
    }

    /** pool-rebuild 与 after-market-batch-scan 互斥：启用其一则关闭另一 */
    private void disablePeerPoolJob(String jobCode) {
        String peer = null;
        if ("pool-rebuild".equals(jobCode)) {
            peer = "after-market-batch-scan";
        } else if ("after-market-batch-scan".equals(jobCode)) {
            peer = "pool-rebuild";
        }
        if (peer == null) {
            return;
        }
        try {
            ScheduleJobDO other = scheduleJobMapper.selectByCode(peer);
            if (other != null && other.getEnabled() != null && other.getEnabled() == 1) {
                scheduleJobMapper.updateEnabled(peer, 0);
                log.info("入池任务互斥：启用 {} 已自动关闭 {}", jobCode, peer);
            }
        } catch (Exception e) {
            log.debug("入池互斥检查跳过: {}", e.getMessage());
        }
    }

    /**
     * 运维「执行一次」：短任务同步；长任务（日线/分钟补齐、扫池等）后台执行并返回 async。
     */
    public Map<String, Object> runOnce(String jobCode) {
        requireJob(jobCode);
        if (ASYNC_MANUAL_JOBS.contains(jobCode)) {
            return startAsyncManual(jobCode);
        }
        invoke(jobCode, true);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("async", false);
        m.put("jobCode", jobCode);
        m.put("message", "执行完成");
        m.put("lastRunAt", LocalDateTime.now().toString());
        return m;
    }

    /** 手动执行进度（含 TDX 脚本行进度），供页面轮询。 */
    public Map<String, Object> runStatus() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("manualRun", snapshotManualRun());
        m.put("tdxScript", tdxScriptBackfillService.status());
        m.put("serverTimeMs", System.currentTimeMillis());
        return m;
    }

    private Map<String, Object> startAsyncManual(String jobCode) {
        synchronized (manualRunRef) {
            ManualRunState cur = manualRunRef.get();
            if (cur != null && cur.running) {
                throw new IllegalStateException("已有手动任务在执行: " + cur.jobCode
                        + "（请等待完成后再点）");
            }
            Map<String, Object> tdxSt = tdxScriptBackfillService.status();
            if (Boolean.TRUE.equals(tdxSt.get("running"))
                    && ("day-collect".equals(jobCode) || "pool-minute-backfill".equals(jobCode))) {
                throw new IllegalStateException("TDX 脚本正在执行，请稍后再试");
            }
            final ManualRunState state = new ManualRunState();
            state.jobCode = jobCode;
            state.jobName = resolveJobName(jobCode);
            state.running = true;
            state.ok = null;
            state.message = "后台执行中…";
            state.startedAt = LocalDateTime.now();
            state.finishedAt = null;
            manualRunRef.set(state);
            manualRunExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        invoke(jobCode, true);
                        state.ok = true;
                        state.message = "执行完成";
                    } catch (Exception e) {
                        state.ok = false;
                        state.message = e.getMessage() == null ? "执行失败" : e.getMessage();
                        log.warn("手动任务后台失败 {}: {}", jobCode, state.message);
                    } finally {
                        state.running = false;
                        state.finishedAt = LocalDateTime.now();
                    }
                }
            });
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("async", true);
        m.put("jobCode", jobCode);
        m.put("jobName", resolveJobName(jobCode));
        m.put("message", longJobStartMessage(jobCode));
        m.put("poll", "/api/schedule/run-status");
        m.put("manualRun", snapshotManualRun());
        return m;
    }

    private String longJobStartMessage(String jobCode) {
        if ("day-collect".equals(jobCode)) {
            return "已开始全市场日线补齐：先同步股票列表，再逐只拉取日线（约五千只，可能需数十分钟）。请看下方进度";
        }
        if ("pool-minute-backfill".equals(jobCode)) {
            return "已开始目标池分钟补齐，请看下方进度";
        }
        if ("pool-rebuild".equals(jobCode)) {
            return "已开始目标池重建（全市场扫描），请看下方进度；完成后刷新最近执行时间";
        }
        return "已开始「" + resolveJobName(jobCode) + "」，请看下方进度";
    }

    private String resolveJobName(String jobCode) {
        try {
            ScheduleJobDO job = scheduleJobMapper.selectByCode(jobCode);
            if (job != null && StringUtils.hasText(job.getJobName())) {
                return job.getJobName();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return jobCode;
    }

    private Map<String, Object> snapshotManualRun() {
        ManualRunState state = manualRunRef.get();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (state == null) {
            m.put("running", false);
            return m;
        }
        m.put("jobCode", state.jobCode);
        m.put("jobName", state.jobName);
        m.put("running", state.running);
        m.put("ok", state.ok);
        m.put("message", state.message);
        m.put("startedAt", state.startedAt == null ? null : state.startedAt.toString());
        m.put("finishedAt", state.finishedAt == null ? null : state.finishedAt.toString());
        if (state.startedAt != null) {
            LocalDateTime end = state.finishedAt != null ? state.finishedAt : LocalDateTime.now();
            m.put("elapsedSec", java.time.Duration.between(state.startedAt, end).getSeconds());
        }
        return m;
    }

    private static final class ManualRunState {
        private String jobCode;
        private String jobName;
        private volatile boolean running;
        private volatile Boolean ok;
        private volatile String message;
        private LocalDateTime startedAt;
        private volatile LocalDateTime finishedAt;
    }

    private void validateTrigger(ScheduleJobDO job) {
        String type = job.getTriggerType() == null ? "" : job.getTriggerType().trim().toUpperCase();
        if ("FIXED_RATE".equals(type)) {
            if (job.getIntervalMs() == null || job.getIntervalMs() < 1000L) {
                throw new IllegalArgumentException("FIXED_RATE 的 intervalMs 至少为 1000");
            }
            return;
        }
        if (!"CRON".equals(type)) {
            throw new IllegalArgumentException("triggerType 仅支持 CRON 或 FIXED_RATE");
        }
        if (!StringUtils.hasText(job.getCronExpr())) {
            throw new IllegalArgumentException("CRON 任务必须填写 cronExpr");
        }
        try {
            CronExpression.parse(job.getCronExpr().trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 cron 表达式: " + e.getMessage());
        }
    }

    private void scheduleOne(ScheduleJobDO job) {
        String code = job.getJobCode();
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                invoke(code, false);
            }
        };
        String type = job.getTriggerType() == null ? "CRON" : job.getTriggerType().trim().toUpperCase();
        ScheduledFuture<?> future;
        if ("FIXED_RATE".equals(type)) {
            long ms = job.getIntervalMs() != null ? job.getIntervalMs() : 10000L;
            // 首次延迟一个周期，避免启动瞬间打满
            future = taskScheduler.getScheduledExecutor().scheduleAtFixedRate(
                    runnable, ms, ms, TimeUnit.MILLISECONDS);
        } else {
            CronTrigger trigger = new CronTrigger(job.getCronExpr().trim(), ZONE);
            future = taskScheduler.schedule(runnable, trigger);
        }
        futures.put(code, future);
        log.info("已注册任务 {} [{}] {}", code, type,
                "FIXED_RATE".equals(type) ? ("intervalMs=" + job.getIntervalMs()) : job.getCronExpr());
    }

    /**
     * @param failLoud 手动「执行一次」为 true：异常向上抛；定时触发为 false：只打日志
     */
    private void invoke(String jobCode, boolean failLoud) {
        try {
            switch (jobCode) {
                case "scan-and-trade":
                    if (!strategyTask.scanAndTrade()) {
                        if (failLoud) {
                            throw new IllegalStateException("scan-and-trade 锁忙，未执行");
                        }
                        log.warn("scan-and-trade 锁忙，定时触发跳过");
                        return;
                    }
                    break;
                case "sync-orders":
                    if (!strategyTask.syncOrders()) {
                        if (failLoud) {
                            throw new IllegalStateException("sync-orders 锁忙，未执行");
                        }
                        log.warn("sync-orders 锁忙，定时触发跳过");
                        return;
                    }
                    break;
                case "settle-after-close":
                    if (!strategyTask.settleAfterClose()) {
                        if (failLoud) {
                            throw new IllegalStateException("settle-after-close 锁忙，未执行");
                        }
                        log.warn("settle-after-close 锁忙，定时触发跳过");
                        return;
                    }
                    break;
                case "after-market-batch-scan":
                    strategyTask.afterMarketBatchScan();
                    break;
                case "market-collect":
                    scheduleJobHandlers.marketCollect();
                    break;
                case "position-pnl-sync":
                    scheduleJobHandlers.positionPnlSync();
                    break;
                case "data-validate":
                    scheduleJobHandlers.dataValidate();
                    break;
                case "factor-daily-rebuild":
                    scheduleJobHandlers.factorDailyRebuild();
                    break;
                case "pool-minute-backfill":
                    scheduleJobHandlers.poolMinuteBackfill();
                    break;
                case "day-collect":
                    scheduleJobHandlers.dayCollect();
                    break;
                case "pool-rebuild":
                    scheduleJobHandlers.poolRebuild();
                    break;
                default:
                    log.warn("未知定时任务编码: {}", jobCode);
                    if (failLoud) {
                        throw new IllegalArgumentException("未知定时任务编码: " + jobCode);
                    }
                    return;
            }
            scheduleJobMapper.updateLastRunAt(jobCode, LocalDateTime.now());
        } catch (IllegalArgumentException e) {
            log.error("定时任务执行失败 {}: {}", jobCode, e.getMessage());
            if (failLoud) {
                throw e;
            }
        } catch (IllegalStateException e) {
            log.error("定时任务执行失败 {}: {}", jobCode, e.getMessage());
            if (failLoud) {
                throw e;
            }
        } catch (Exception e) {
            log.error("定时任务执行失败 {}: {}", jobCode, e.getMessage(), e);
            if (failLoud) {
                throw new IllegalStateException("任务执行失败: " + e.getMessage(), e);
            }
        }
    }

    private void cancelAll() {
        for (Map.Entry<String, ScheduledFuture<?>> e : futures.entrySet()) {
            cancelQuiet(e.getValue());
        }
        futures.clear();
    }

    private static void cancelQuiet(ScheduledFuture<?> f) {
        if (f != null) {
            f.cancel(false);
        }
    }

    private ScheduleJobDO requireJob(String jobCode) {
        ScheduleJobDO job = scheduleJobMapper.selectByCode(jobCode);
        if (job == null) {
            throw new IllegalArgumentException("任务不存在: " + jobCode);
        }
        return job;
    }

    private Map<String, Object> toView(ScheduleJobDO job, boolean masterOn) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        boolean itemOn = job.getEnabled() != null && job.getEnabled() == 1;
        m.put("jobCode", job.getJobCode());
        m.put("jobName", job.getJobName());
        m.put("triggerType", job.getTriggerType());
        m.put("cronExpr", job.getCronExpr());
        m.put("intervalMs", job.getIntervalMs());
        m.put("enabled", itemOn);
        m.put("implemented", job.getImplemented() != null && job.getImplemented() == 1);
        m.put("effective", masterOn && itemOn);
        m.put("scheduled", futures.containsKey(job.getJobCode()));
        m.put("lastRunAt", job.getLastRunAt() == null ? null : job.getLastRunAt().toString());
        m.put("remark", job.getRemark());
        m.put("updatedAt", job.getUpdatedAt() == null ? null : job.getUpdatedAt().toString());
        m.put("detail", ScheduleJobGuide.toViewMap(job.getJobCode()));
        return m;
    }

    private void ensureSchemaAndSeed() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS `sys_schedule_job` ("
                        + "`id` BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "`job_code` VARCHAR(64) NOT NULL,"
                        + "`job_name` VARCHAR(128) NOT NULL,"
                        + "`trigger_type` VARCHAR(16) NOT NULL DEFAULT 'CRON',"
                        + "`cron_expr` VARCHAR(64) DEFAULT NULL,"
                        + "`interval_ms` BIGINT DEFAULT NULL,"
                        + "`enabled` TINYINT NOT NULL DEFAULT 0,"
                        + "`implemented` TINYINT NOT NULL DEFAULT 1,"
                        + "`last_run_at` DATETIME DEFAULT NULL,"
                        + "`remark` VARCHAR(512) DEFAULT NULL,"
                        + "`created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,"
                        + "`updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                        + "UNIQUE KEY `uk_job_code` (`job_code`)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        );
        // implemented=1：本地/模拟路径已可完整调度；=0：缺外部 API，页面标「未实现」
        seed("market-collect", "行情采集", "FIXED_RATE", null, 30000L, 0,
                "可选宽睿 MDS（quant.kuangrui.*）；默认关时仍为本地骨架/回退");
        seed("scan-and-trade", "实盘分钟扫描交易", "CRON",
                "0 */1 9-11,13-15 * * MON-FRI", null, 1, "工作日交易时段每分钟扫描（模拟账本）");
        seed("sync-orders", "订单状态同步", "FIXED_RATE", null, 10000L, 1,
                "本地桩：SUBMITTED→FILLED 并改仓/回写；真券商对账待 API");
        seed("position-pnl-sync", "持仓盈亏同步", "CRON",
                "0 */1 9-15 * * MON-FRI", null, 1,
                "本地成本+市值浮盈已可用；券商持仓对账待 API");
        seed("settle-after-close", "收盘清算与K线聚合", "CRON",
                "0 30 15 * * MON-FRI", null, 1,
                "本地权益日结 + K 线聚合；真实行情增量仍依赖 market-collect/外部 API");
        seed("pool-rebuild", "全市场入池扫描", "CRON",
                "0 10 15 * * MON-FRI", null, 1,
                "全市场扫描筛选可入选标的，覆盖唯一目标池；与 after-market-batch-scan 启用其一即可");
        seed("after-market-batch-scan", "盘后入池扫描", "CRON",
                "0 0 16 * * MON-FRI", null, 1,
                "工作日 16:00 再次覆盖唯一目标池；与 pool-rebuild 启用其一即可");
        seed("data-validate", "数据校验", "CRON",
                "0 0 17 * * MON-FRI", null, 1,
                "分层：universe→market_daily；目标池→market_1min；外部抽样对账待 API");
        seed("factor-daily-rebuild", "日频因子重算", "CRON",
                "0 0 15 * * MON-FRI", null, 1,
                "由日线重算 factor_daily；建议在 pool-rebuild 前；亦可由 pool-rebuild 内预刷新");
        seed("day-collect", "全市场日线补齐(TDX)", "CRON",
                "0 30 15 * * MON-FRI", null, 1,
                "执行前默认同步 stock_basic 全市场(~5000+)；无日线补近1年，有则增量；需 tdx-script.enabled");
        seed("pool-minute-backfill", "目标池分钟补齐(TDX)", "CRON",
                "0 20 15 * * MON-FRI", null, 1,
                "池内尽量拉满节点深度(~90日)并补到最近；需 quant.tdx-script.enabled=true");
        // 纠正旧库标记（不改 enabled）
        syncJobMeta("market-collect", 0,
                "可选宽睿 MDS（-Pkuangrui + quant.kuangrui.mds.enabled）；默认关时本地骨架/回退");
        syncJobMeta("position-pnl-sync", 1, "本地成本+市值浮盈已可用；券商持仓对账待 API");
        syncJobMeta("data-validate", 1,
                "分层：universe→market_daily；目标池→market_1min；外部抽样对账待 API");
        syncJobMeta("factor-daily-rebuild", 1,
                "由日线重算 factor_daily；建议在 pool-rebuild 前；亦可由 pool-rebuild 内预刷新");
        syncJobMeta("day-collect", 1,
                "执行前默认同步 stock_basic 全市场(~5000+)；无日线补近1年，有则增量；需 tdx-script.enabled");
        syncJobMeta("pool-minute-backfill", 1,
                "池内尽量拉满节点深度(~90日)并补到最近；需 quant.tdx-script.enabled=true");
        // 纠正展示名（旧库可能仍是旧名称）
        jdbcTemplate.update(
                "UPDATE sys_schedule_job SET job_name=? WHERE job_code=?",
                "全市场日线补齐(TDX)", "day-collect");
        jdbcTemplate.update(
                "UPDATE sys_schedule_job SET job_name=? WHERE job_code=?",
                "目标池分钟补齐(TDX)", "pool-minute-backfill");
        syncJobMeta("scan-and-trade", 1, "仅扫描唯一目标池（trade_pool status=1）");
        syncJobMeta("sync-orders", 1, "本地桩：SUBMITTED→FILLED 并改仓/回写；真券商对账待 API");
        syncJobMeta("settle-after-close", 1,
                "本地权益日结 + K 线聚合；真实行情增量仍依赖 market-collect/外部 API");
        syncJobMeta("pool-rebuild", 1, "全市场扫描覆盖唯一目标池；与 after-market-batch-scan 互斥（启用其一自动关另一）");
        syncJobMeta("after-market-batch-scan", 1, "工作日 16:00 覆盖唯一目标池；与 pool-rebuild 互斥（启用其一自动关另一）");
    }

    private void syncJobMeta(String code, int implemented, String remark) {
        jdbcTemplate.update(
                "UPDATE sys_schedule_job SET implemented = ?, remark = ? WHERE job_code = ?",
                implemented, remark, code);
    }

    private void seed(String code, String name, String type, String cron, Long intervalMs,
                      int implemented, String remark) {
        scheduleJobMapper.insertIgnore(ScheduleJobDO.builder()
                .jobCode(code)
                .jobName(name)
                .triggerType(type)
                .cronExpr(cron)
                .intervalMs(intervalMs)
                .enabled(0)
                .implemented(implemented)
                .remark(remark)
                .build());
    }
}
