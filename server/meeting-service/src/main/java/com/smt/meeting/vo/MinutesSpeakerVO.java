package com.smt.meeting.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MinutesSpeakerVO {

    private String speakerKey;
    private String displayName;
    private String color;
}
