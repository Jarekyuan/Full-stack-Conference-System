package com.smt.meeting.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 冲突会议简报（用于 40001 冲突详情与可用性查询的 conflicts 列表，对照 docs/04 4.3）
 */
@Data
public class MeetingConflictVO {

    private Long meetingId;
    private String title;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long organiserId;
    /** 组织人姓名（同库 JOIN sys_user 直出，展示用） */
    private String organiserName;
}
