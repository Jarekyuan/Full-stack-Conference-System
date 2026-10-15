package com.smt.meeting.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待审批列表项（`GET /approvals/pending`，契约见 docs/04 3.2）
 *
 * 为什么不用 MeetingVO：列表 VO 按场景裁剪掉了 remark，而**审批人正是靠 remark 做判断**。
 * 少这一个字段，审批人就得逐个点进详情——按场景裁剪不是"字段越少越好"，是"这个场景要什么给什么"。
 */
@Data
public class ApprovalVO {

    /** 会议 ID（审批动作的路径参数 /approvals/{meetingId}/pass） */
    private Long meetingId;
    private String title;
    private Long roomId;
    private String roomName;
    private Long organiserId;
    private String organiserName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 用途/事由（申请人填的备注） */
    private String remark;
    private LocalDateTime createdAt;
}
