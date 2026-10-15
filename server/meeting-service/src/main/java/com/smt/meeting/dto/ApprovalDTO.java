package com.smt.meeting.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 审批动作入参（通过/拒绝共用，对照 docs/04 4.7 4.8）
 *
 * `comment` 在**拒绝**时必填（D-3：拒绝必须给理由）、**通过**时可空。
 * 因此不在 DTO 上挂 `@NotBlank`（那会把"通过"也变成必须填意见），
 * 必填校验放在 ApprovalService.reject 里，错误码仍是 10001。
 */
@Data
public class ApprovalDTO {

    @Size(max = 500, message = "审批意见最长 500 字符")
    private String comment;
}
