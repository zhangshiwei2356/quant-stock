package com.quant.stock.task;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.mapper.ScheduleJobMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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

    private final ScheduleJobMapper scheduleJobMapper;
    private final StrategyTask strategyTask;
    private final ScheduleJobHandlers scheduleJobHandlers;
    private final QuantProperties quantProperties;
    private final JdbcTemplate jdbcTemplate;

    private final ThreadPoolTaskScheduler taskScheduler = createScheduler();
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<String, ScheduledFuture<?>>();

    private static ThreadPoolTaskScheduler createScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(4);
        s.setThreadNamePrefix("quant-job-");
        s.setRemoveOnCancelPolicy(true);
        s.initialize();
        return s;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureSchemaAndSeed();
        reloadAll();
    }

    @PreDestroy
    public void destroy() {
        cancelAll();
        taskScheduler.shutdown();
    }

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

    public List<Map<String, Object>> listJobs() {
        boolean masterOn = quantProperties.getSchedule().isEnabled();
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (ScheduleJobDO job : scheduleJobMapper.selectAll()) {
            list.add(toView(job, masterOn));
        }
        return list;
    }

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
        return m;
    }

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
        reloadAll();
        return toView(requireJob(jobCode), quantProperties.getSchedule().isEnabled());
    }

    public Map<String, Object> toggle(String jobCode, Boolean enabled) {
        ScheduleJobDO existing = requireJob(jobCode);
        int next;
        if (enabled != null) {
            next = Boolean.TRUE.equals(enabled) ? 1 : 0;
        } else {
            next = (existing.getEnabled() != null && existing.getEnabled() == 1) ? 0 : 1;
        }
        scheduleJobMapper.updateEnabled(jobCode, next);
        reloadAll();
        return toView(requireJob(jobCode), quantProperties.getSchedule().isEnabled());
    }

    public void runOnce(String jobCode) {
        requireJob(jobCode);
        invoke(jobCode);
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
                invoke(code);
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

    private void invoke(String jobCode) {
        try {
            switch (jobCode) {
                case "scan-and-trade":
                    strategyTask.scanAndTrade();
                    break;
                case "sync-orders":
                    strategyTask.syncOrders();
                    break;
                case "settle-after-close":
                    strategyTask.settleAfterClose();
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
                case "pool-rebuild":
                    scheduleJobHandlers.poolRebuild();
                    break;
                default:
                    log.warn("未知定时任务编码: {}", jobCode);
                    return;
            }
            scheduleJobMapper.updateLastRunAt(jobCode, LocalDateTime.now());
        } catch (Exception e) {
            log.error("定时任务执行失败 {}: {}", jobCode, e.getMessage(), e);
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
                "未实现：待接入真实行情 API（本地仅有骨架）");
        seed("scan-and-trade", "实盘分钟扫描交易", "CRON",
                "0 */1 9-11,13-15 * * MON-FRI", null, 1, "工作日交易时段每分钟扫描（模拟账本）");
        seed("sync-orders", "订单状态同步", "FIXED_RATE", null, 10000L, 0,
                "未实现：待接入券商委托查询 API（当前仅本地桩）");
        seed("position-pnl-sync", "持仓盈亏同步", "CRON",
                "0 */1 9-15 * * MON-FRI", null, 0,
                "未实现：待接入券商持仓/成本 API（本地仅有骨架）");
        seed("settle-after-close", "收盘清算与K线聚合", "CRON",
                "0 30 15 * * MON-FRI", null, 0,
                "未实现：待接入真实行情 API（账户清算本地可用，拉取/聚合依赖行情源）");
        seed("pool-rebuild", "全市场入池扫描", "CRON",
                "0 10 15 * * MON-FRI", null, 1,
                "全市场扫描筛选可入选标的，覆盖唯一目标池；与 after-market-batch-scan 启用其一即可");
        seed("after-market-batch-scan", "盘后入池扫描", "CRON",
                "0 0 16 * * MON-FRI", null, 1,
                "工作日 16:00 再次覆盖唯一目标池；与 pool-rebuild 启用其一即可");
        seed("data-validate", "数据校验", "CRON",
                "0 0 17 * * MON-FRI", null, 0,
                "未实现：待接入外部行情对账 API（本地仅有骨架）");
        // 纠正旧库标记（不改 enabled）
        syncJobMeta("market-collect", 0, "未实现：待接入真实行情 API（本地仅有骨架）");
        syncJobMeta("position-pnl-sync", 0, "未实现：待接入券商持仓/成本 API（本地仅有骨架）");
        syncJobMeta("data-validate", 0, "未实现：待接入外部行情对账 API（本地仅有骨架）");
        syncJobMeta("scan-and-trade", 1, "仅扫描唯一目标池（trade_pool status=1）");
        syncJobMeta("sync-orders", 0, "未实现：待接入券商委托查询 API（当前仅本地桩）");
        syncJobMeta("settle-after-close", 0,
                "未实现：待接入真实行情 API（账户清算本地可用，拉取/聚合依赖行情源）");
        syncJobMeta("pool-rebuild", 1, "全市场扫描覆盖唯一目标池；与 after-market-batch-scan 启用其一即可");
        syncJobMeta("after-market-batch-scan", 1, "工作日 16:00 覆盖唯一目标池；与 pool-rebuild 启用其一即可");
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
