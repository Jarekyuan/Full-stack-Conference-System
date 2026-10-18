package com.smt.meeting.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class MultipartPartVO {

    private Integer partNumber;
    private String url;
    private LocalDateTime expiresAt;
}
