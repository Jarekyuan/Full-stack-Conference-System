package com.smt.meeting.vo;

import lombok.Data;

@Data
public class TranscriptSegmentVO {

    private Long id;
    private Integer seq;
    private String speakerKey;
    private Long startMs;
    private Long endMs;
    private String text;
}
