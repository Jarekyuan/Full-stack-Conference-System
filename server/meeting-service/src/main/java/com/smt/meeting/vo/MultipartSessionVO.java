package com.smt.meeting.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class MultipartSessionVO {

    private String uploadId;
    private String objectKey;
    private Integer chunkSize;
    private Integer totalParts;
    private Integer status;
    private List<UploadedPartVO> uploadedParts;
    private List<Integer> missingPartNumbers;
    private LocalDateTime expiresAt;
}
