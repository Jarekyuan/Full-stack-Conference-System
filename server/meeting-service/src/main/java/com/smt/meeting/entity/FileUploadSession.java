package com.smt.meeting.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分片上传会话（对应表 file_upload_session，字段见 docs/03 数据库设计）
 */
@Data
public class FileUploadSession {

    private Long id;
    private String uploadId;
    /** 业务类型：MINUTES_AUDIO */
    private String bizType;
    /** 业务主键：会议 id */
    private Long bizId;
    private String objectKey;
    private String originalName;
    private Long sizeBytes;
    private String contentType;
    /** 分片大小（字节） */
    private Integer chunkSize;
    private Integer totalParts;
    /** 0 进行中 / 10 已完成 / 20 已中止 / 30 已过期 */
    private Integer status;
    /** 完成后关联的 file_object.id */
    private Long fileId;
    private Long createdBy;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
