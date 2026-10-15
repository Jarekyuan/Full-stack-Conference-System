package com.smt.meeting.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 已处理记录项（`GET /approvals/history`，契约见 docs/04 3.2）
 *
 * 注意 `meetingStatus` 是会议**当前**状态：审批那一刻它是 20，之后可能已被取消（50）、
 * 结束（40）或被组织人改期（回 10）。审批中心展示时必须用它，而不是"审批通过 = 已预约"的想当然。
 */
@Data
public class ApprovalHistoryVO {

    private Long approvalId;
    private Long meetingId;
    private String title;
    private String roomName;
    private String organiserName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** PASS / REJECT */
    private String action;
    private String comment;
    private Long approverId;
    private String approverName;
    /** 会议当前状态（10/20/30/40/50/60） */
    private Integer meetingStatus;
    /** 审批处理时间 */
    private LocalDateTime handledAt;
}
