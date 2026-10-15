package com.smt.meeting.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 取消会议入参（docs/04：POST /meetings/{id}/cancel）
 */
@Data
public class MeetingCancelDTO {

    /** 取消原因（可选） */
    @Size(max = 200, message = "取消原因最长 200 字符")
    private String reason;
}
