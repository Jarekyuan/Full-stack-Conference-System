package com.smt.meeting.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 纪要发言人（对应表 meeting_minutes_speaker，字段见 docs/03 数据库设计）
 */
@Data
public class MinutesSpeaker {

    private Long id;
    private Long minutesId;
    /** ASR 原始说话人编号 */
    private String speakerKey;
    /** 展示名，默认“说话人 N” */
    private String displayName;
    /** 展示色 #RRGGBB */
    private String color;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
