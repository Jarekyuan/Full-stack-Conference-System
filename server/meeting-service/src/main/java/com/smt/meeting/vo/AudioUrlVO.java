package com.smt.meeting.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class AudioUrlVO {

    private String url;
    private LocalDateTime expiresAt;
}
