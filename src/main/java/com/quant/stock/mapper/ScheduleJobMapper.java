package com.quant.stock.mapper;

import com.quant.stock.task.dto.ScheduleJobDO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务配置 Mapper：读写 sys_schedule_job 表。
 */
public interface ScheduleJobMapper {

    /** 查询全部任务行，通常按 job_code 排序。 */
    List<ScheduleJobDO> selectAll();

    /** 按 job_code 查询单条任务配置。 */
    ScheduleJobDO selectByCode(@Param("jobCode") String jobCode);

    /** 按 job_code 更新 cron、描述等非 enabled 字段。 */
    int updateByCode(ScheduleJobDO row);

    /** 仅更新指定任务的 enabled 开关。 */
    int updateEnabled(@Param("jobCode") String jobCode, @Param("enabled") int enabled);

    /** 记录任务最近一次执行完成时间。 */
    int updateLastRunAt(@Param("jobCode") String jobCode, @Param("lastRunAt") LocalDateTime lastRunAt);

    /** 统计 sys_schedule_job 总行数。 */
    int countAll();

    /** 插入新任务，主键冲突时忽略（幂等种子）。 */
    int insertIgnore(ScheduleJobDO row);
}
