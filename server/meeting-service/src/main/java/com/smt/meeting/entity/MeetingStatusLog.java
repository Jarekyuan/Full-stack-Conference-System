package com.smt.meeting.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会议状态变更日志（对应表 meeting_status_log）
 * 每次状态迁移由状态机在同一事务内写入；创建记录 from_status = NULL
 */
@Data
public class MeetingStatusLog {

    private Long id;
    private Long meetingId;
    /** 变更前状态（创建时为 NULL） */
    private Integer fromStatus;
    private Integer toStatus;
    /** 事件名（MeetingEventEnum.name()）：RESERVE/APPROVE/REJECT/CANCEL/START/END/EXTEND */
    private String event;
    /** 操作人（系统任务为 NULL） */
    private Long operatorId;
    private String remark;
    private LocalDateTime createdAt;
}
