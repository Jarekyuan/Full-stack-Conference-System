package com.smt.meeting.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量可用性查询入参（对照 docs/04 4.3）
 */
@Data
public class AvailabilityQueryDTO {

    /**
     * 会议室 id 列表。
     * 上限 50（评审 R7）：每个 id 会触发一次冲突检测查询，而默认 2MB 请求体可塞约 20 万个数值
     * → 单个请求线程上挂 40 万次 DB 往返（登录即可调用）。会议室总数远小于 50，上限不影响正常用法。
     * 元素用**容器元素约束**（`List<@Positive Long>`）：@NotEmpty 只管"非空"，管不住 `[-1, 999999]` 这种越界 id。
     */
    @NotEmpty(message = "会议室列表不能为空")
    @Size(max = 50, message = "一次最多查询 50 个会议室")
    private List<@NotNull(message = "会议室 id 不能为空") @Positive(message = "会议室 id 必须为正整数") Long> roomIds;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    /** 修改会议场景排除自身（创建传 null） */
    private Long excludeMeetingId;
}
