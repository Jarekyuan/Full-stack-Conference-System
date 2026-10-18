package com.smt.meeting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建预约入参（对照 docs/04 4.4）
 * 幂等头 X-Idempotency-Token 由 T2.5 的 AOP 消费，不在 DTO 内
 */
@Data
public class MeetingCreateDTO {

    @NotBlank(message = "会议主题不能为空")
    @Size(max = 100, message = "主题最长 100 字符")
    private String title;

    @NotNull(message = "会议室不能为空")
    private Long roomId;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    @Size(max = 500, message = "备注最长 500 字符")
    private String remark;

    /** 参会人（可空；仅系统用户，v1.3 起不区分外部参会人） */
    @Valid
    private Participants participants;

    @Data
    public static class Participants {
        /**
         * 内部参会人（sys_user.id；重复 id 由去重逻辑兜底）。
         * T6.2 评审 R6：上限 100，元素必须为正数；**存在性与启用状态在 Service 里查库校验**
         * （停用/不存在 → 10001），不能只靠前端选人列表隐藏。
         */
        @Size(max = 100, message = "参会人最多 100 人")
        private List<@NotNull(message = "参会人 id 不能为空")
                     @Positive(message = "参会人 id 必须为正数") Long> userIds;
    }
}
