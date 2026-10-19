package com.smt.meeting.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会议纪要（对应表 meeting_minutes，一场会议一份，字段见 docs/03 数据库设计）
 */
@Data
public class MeetingMinutes {

    private Long id;
    private Long meetingId;
    /** 会议室ID（冗余，便于排障） */
    private Long roomId;
    private Long audioFileId;
    private String transcriptSource;
    private Long durationMs;
    /** 0 未生成 / 10 待生成 / 20 生成中 / 30 成功 / 40 失败 */
    private Integer aiStatus;
    /** 结构化纪要（JSON） */
    private String summaryJson;
    private String aiModel;
    /** 用户可读失败原因 */
    private String aiError;
    private Integer aiAttempts;
    private LocalDateTime aiStartedAt;
    private LocalDateTime aiFinishedAt;
    /** 0 未转写 / 10 待识别 / 20 识别中 / 30 成功 / 40 失败 */
    private Integer asrStatus;
    private String asrTaskId;
    private String asrError;
    private Integer asrAttempts;
    private LocalDateTime asrStartedAt;
    private LocalDateTime asrFinishedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
