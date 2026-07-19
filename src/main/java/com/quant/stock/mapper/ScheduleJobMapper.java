package com.quant.stock.mapper;

import com.quant.stock.task.dto.ScheduleJobDO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduleJobMapper {

    List<ScheduleJobDO> selectAll();

    ScheduleJobDO selectByCode(@Param("jobCode") String jobCode);

    int updateByCode(ScheduleJobDO row);

    int updateEnabled(@Param("jobCode") String jobCode, @Param("enabled") int enabled);

    int updateLastRunAt(@Param("jobCode") String jobCode, @Param("lastRunAt") LocalDateTime lastRunAt);

    int countAll();

    int insertIgnore(ScheduleJobDO row);
}
