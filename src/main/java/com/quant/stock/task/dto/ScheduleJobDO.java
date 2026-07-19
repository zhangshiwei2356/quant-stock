package com.quant.stock.task.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleJobDO {
    private Long id;
    private String jobCode;
    private String jobName;
    /** CRON / FIXED_RATE */
    private String triggerType;
    private String cronExpr;
    private Long intervalMs;
    /** 1 启用 0 停用 */
    private Integer enabled;
    /** 1 已实现 0 占位 */
    private Integer implemented;
    private LocalDateTime lastRunAt;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
