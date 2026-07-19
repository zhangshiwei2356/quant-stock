package com.quant.stock.task.dto;

import lombok.Data;

/**
 * 更新定时任务：字段均为可选，仅提交的字段会写入。
 */
@Data
public class ScheduleJobUpdateRequest {
    private String jobName;
    /** CRON / FIXED_RATE */
    private String triggerType;
    private String cronExpr;
    private Long intervalMs;
    /** true/false 或 1/0 */
    private Boolean enabled;
    private String remark;
}
