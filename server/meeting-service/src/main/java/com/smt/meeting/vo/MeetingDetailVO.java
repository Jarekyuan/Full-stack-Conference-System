package com.smt.meeting.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会议详情 VO（docs/04 3.2：GET /meetings/{id}）
 * 相比列表项补充：备注/取消信息/参会人明细/状态时间线。
 * 设计注明（docs/05 T2.8 验收）：会议信息**公开可查**（无隐私约定）——详情不限制"非参与人"访问。
 */
@Data
public class MeetingDetailVO {

    private Long id;
    private String title;
    private Long roomId;
    private String roomName;
    private Long organiserId;
    private String organiserName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;
    private String remark;
    private String cancelReason;
    private LocalDateTime cancelTime;
    private Long recurringId;
    private Integer version;
    private LocalDateTime createdAt;

    /** 参会人列表（含姓名/邮箱，JOIN sys_user） */
    private List<ParticipantVO> participants;
    /** 状态变更时间线（升序，含操作人姓名） */
    private List<StatusLogVO> logs;

    @Data
    public static class ParticipantVO {
        private Long userId;
        private String realName;
        private String email;
    }

    @Data
    public static class StatusLogVO {
        private Integer fromStatus;
        private Integer toStatus;
        /** RESERVE/APPROVE/REJECT/CANCEL/START/END/EXTEND */
        private String event;
        /** 操作人（系统任务为 NULL） */
        private Long operatorId;
        private String operatorName;
        private String remark;
        private LocalDateTime createdAt;
    }
}
