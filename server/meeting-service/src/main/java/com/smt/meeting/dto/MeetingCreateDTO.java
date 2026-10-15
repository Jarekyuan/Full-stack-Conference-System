package com.smt.meeting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    /** 参会人（可空；内部用户 + 外部参会人） */
    @Valid
    private Participants participants;

    @Data
    public static class Participants {
        /** 内部参会人（sys_user.id） */
        private List<Long> userIds;
        /** 外部参会人（客户等无账号参与者） */
        @Valid
        private List<External> externals;
    }

    @Data
    public static class External {
        @NotBlank(message = "外部参会人姓名不能为空")
        @Size(max = 50, message = "外部参会人姓名最长 50 字符")
        private String name;

        @Email(message = "邮箱格式不正确")
        @Size(max = 100, message = "邮箱最长 100 字符")
        private String email;
    }
}
