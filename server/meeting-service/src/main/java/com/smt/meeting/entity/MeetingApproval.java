package com.smt.meeting.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批记录（对应表 meeting_approval，字段见 db/01-schema.sql）
 *
 * 为什么是**历史表**而不是会议上的两个字段：被拒（60）之后会议可改期重送审（R-14 的 20 → 10），
 * 同一会议因此可能有多条审批记录——"谁在什么时候因为什么批的"必须逐条留痕，
 * 用 meeting 上的 approver_id/approve_time 会把历史覆盖掉。
 */
@Data
public class MeetingApproval {

    private Long id;
    private Long meetingId;
    /** 审批人（sys_user.id） */
    private Long approverId;
    /** PASS 通过 / REJECT 拒绝（存字符串字面量，不用枚举名以外的约定） */
    private String action;
    /** 审批意见（拒绝时必填，通过时可空） */
    private String comment;
    private LocalDateTime createdAt;
}
