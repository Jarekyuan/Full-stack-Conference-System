package com.smt.meeting.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量可用性查询入参（对照 docs/04 4.3）
 */
@Data
public class AvailabilityQueryDTO {

    @NotEmpty(message = "会议室列表不能为空")
    private List<Long> roomIds;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    /** 修改会议场景排除自身（创建传 null） */
    private Long excludeMeetingId;
}
