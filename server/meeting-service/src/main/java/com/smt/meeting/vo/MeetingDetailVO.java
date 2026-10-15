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

    /** 参会人列表（JOIN sys_user 取姓名；**不含 email**，见 ParticipantVO 注释） */
    private List<ParticipantVO> participants;
    /** 状态变更时间线（升序，含操作人姓名） */
    private List<StatusLogVO> logs;

    /**
     * 参会人精简信息。
     * 不含 email（评审 R8）：详情对任意登录用户开放且 id 可枚举，返回邮箱 = 可翻页的通讯录，
     * 而前端从不渲染它——零业务价值的 PII 暴露。需要邮箱的场景（M4 通知）走 /internal/users/batch。
     */
    @Data
    public static class ParticipantVO {
        private Long userId;
        private String realName;
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
