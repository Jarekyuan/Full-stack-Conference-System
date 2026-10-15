package com.smt.meeting.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会议主表（对应表 meeting，字段见 db/01-schema.sql）
 * 状态流转统一走 {@link com.smt.meeting.statemachine.MeetingStateMachine}，禁止业务代码直接 set status
 */
@Data
public class Meeting {

    private Long id;
    private String title;
    private Long roomId;
    /** 组织人（sys_user.id） */
    private Long organiserId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 10 待审批/20 已预约/30 进行中/40 已结束/50 已取消/60 已拒绝 */
    private Integer status;
    private String remark;
    private String cancelReason;
    private LocalDateTime cancelTime;
    /** 周期系列 ID（NULL = 非周期会议，P1） */
    private Long recurringId;
    /** 乐观锁版本号（修改会议时校验，T2.10） */
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
