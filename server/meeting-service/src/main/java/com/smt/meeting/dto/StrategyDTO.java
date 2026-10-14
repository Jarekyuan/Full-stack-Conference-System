package com.smt.meeting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 会议室策略入参（ADMIN，全量更新）
 * 范围约定：advance_days 1-365；max_duration 15-1440；cancel_deadline 0-1440；buffer 0-60；开关 0/1
 */
@Data
public class StrategyDTO {

    @NotNull(message = "审批开关不能为空")
    @Min(value = 0, message = "取值 0 或 1")
    @Max(value = 1, message = "取值 0 或 1")
    private Integer needApproval;

    @NotNull(message = "提前预约天数不能为空")
    @Min(value = 1, message = "提前预约天数 1-365")
    @Max(value = 365, message = "提前预约天数 1-365")
    private Integer advanceDays;

    @NotNull(message = "单场最长时长不能为空")
    @Min(value = 15, message = "单场时长 15-1440 分钟")
    @Max(value = 1440, message = "单场时长 15-1440 分钟")
    private Integer maxDurationMinutes;

    @NotNull(message = "取消截止不能为空")
    @Min(value = 0, message = "取消截止 0-1440 分钟")
    @Max(value = 1440, message = "取消截止 0-1440 分钟")
    private Integer cancelDeadlineMinutes;

    @NotNull(message = "锁定缓冲不能为空")
    @Min(value = 0, message = "锁定缓冲 0-60 分钟")
    @Max(value = 60, message = "锁定缓冲 0-60 分钟")
    private Integer bufferMinutes;

    @NotNull(message = "周期会议开关不能为空")
    @Min(value = 0, message = "取值 0 或 1")
    @Max(value = 1, message = "取值 0 或 1")
    private Integer allowRecurring;
}
