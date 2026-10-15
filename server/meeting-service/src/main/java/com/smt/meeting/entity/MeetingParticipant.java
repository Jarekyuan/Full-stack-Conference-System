package com.smt.meeting.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会议参会人（对应表 meeting_participant）
 * 内部参会人存 user_id；外部参会人存 external_name/external_email（支持客户等无账号参与者）
 */
@Data
public class MeetingParticipant {

    private Long id;
    private Long meetingId;
    /** 用户ID（外部参会人为 NULL） */
    private Long userId;
    /** 0 内部 / 1 外部 */
    private Integer isExternal;
    private String externalName;
    private String externalEmail;
    private LocalDateTime createdAt;
}
