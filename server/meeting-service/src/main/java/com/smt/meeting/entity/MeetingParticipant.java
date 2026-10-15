package com.smt.meeting.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会议参会人（对应表 meeting_participant，N:N 中间表）
 * v1.3：不区分外部参会人（参会人限定系统用户），联合唯一键防重复
 */
@Data
public class MeetingParticipant {

    private Long id;
    private Long meetingId;
    /** 参会人用户ID（sys_user.id） */
    private Long userId;
    private LocalDateTime createdAt;
}
