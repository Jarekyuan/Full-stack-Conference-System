package com.smt.meeting.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class MultipartInitVO {

    private String uploadId;
    private String objectKey;
    private Integer chunkSize;
    private Integer totalParts;
    private LocalDateTime expiresAt;
}
