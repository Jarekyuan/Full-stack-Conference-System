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
    /** 已完成会话关联的 file_object.id；进行中为 null */
    private Long fileId;
    private List<UploadedPartVO> uploadedParts;
    private List<Integer> missingPartNumbers;
    private LocalDateTime expiresAt;
}
