package com.quant.stock.controller;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.task.DynamicScheduleService;
import com.quant.stock.task.dto.ScheduleJobUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 定时任务：列表 / 启停 / 改 cron / 立即执行 / 重载调度。
 * 需 {@code quant.db-enabled=true}；任务配置存 MySQL {@code sys_schedule_job}。
 */
@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
public class ScheduleController {

    private final QuantProperties props;
    private final ObjectProvider<DynamicScheduleService> scheduleServiceProvider;

    /** 调度器总状态：是否启用、已注册任务数及提示信息。 */
    @GetMapping
    public Map<String, Object> status() {
        DynamicScheduleService svc = scheduleServiceProvider.getIfAvailable();
        if (svc != null) {
            return svc.status();
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", props.getSchedule().isEnabled());
        m.put("schedulerActive", false);
        m.put("registeredCount", 0);
        m.put("hint", "需开启 quant.db-enabled=true 后才能使用动态调度与库表管理");
        m.put("jobs", Collections.emptyList());
        return m;
    }

    /** 列出 sys_schedule_job 中全部任务配置。 */
    @GetMapping("/jobs")
    public Map<String, Object> jobs() {
        DynamicScheduleService svc = requireService();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", props.getSchedule().isEnabled());
        m.put("jobs", svc.listJobs());
        return m;
    }

    /** 更新指定任务的 cron、描述等字段并重载调度。 */
    @PutMapping("/jobs/{jobCode}")
    public Map<String, Object> update(@PathVariable String jobCode,
                                      @RequestBody ScheduleJobUpdateRequest body) {
        try {
            return requireService().updateJob(jobCode, body == null ? new ScheduleJobUpdateRequest() : body);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 启用或停用指定定时任务。 */
    @PostMapping("/jobs/{jobCode}/toggle")
    public Map<String, Object> toggle(@PathVariable String jobCode,
                                      @RequestParam(required = false) Boolean enabled) {
        try {
            return requireService().toggle(jobCode, enabled);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 立即手动触发一次指定任务（与 cron 无关）。长任务后台执行并返回 async。 */
    @PostMapping("/jobs/{jobCode}/run")
    public Map<String, Object> runOnce(@PathVariable String jobCode) {
        try {
            return requireService().runOnce(jobCode);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "执行失败: " + e.getMessage());
        }
    }

    /** 手动「执行一次」进度（含 TDX 脚本行进度），供页面轮询。 */
    @GetMapping("/run-status")
    public Map<String, Object> runStatus(javax.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        return requireService().runStatus();
    }

    /** 从数据库重新加载全部任务并刷新 Spring 调度注册。 */
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        requireService().reloadAll();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("message", "已按库表重载调度");
        m.putAll(requireService().status());
        return m;
    }

    private DynamicScheduleService requireService() {
        DynamicScheduleService svc = scheduleServiceProvider.getIfAvailable();
        if (svc == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "动态调度未启用（需要 quant.db-enabled=true）");
        }
        return svc;
    }
}
