package com.smt.meeting.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 转写分段（对应表 meeting_transcript_segment，字段见 docs/03 数据库设计）
 */
@Data
public class TranscriptSegment {

    private Long id;
    private Long minutesId;
    /** 从 0 开始的顺序号 */
    private Integer seq;
    /** ASR 原始说话人编号 */
    private String speakerKey;
    /** 相对音频开始的毫秒偏移 */
    private Long startMs;
    private Long endMs;
    private String text;
    private LocalDateTime createdAt;
}
