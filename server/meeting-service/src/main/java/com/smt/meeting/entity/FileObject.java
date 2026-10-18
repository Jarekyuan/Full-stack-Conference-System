package com.smt.meeting.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件对象元数据（对应表 file_object，字段见 docs/03 数据库设计）
 */
@Data
public class FileObject {

    private Long id;
    /** 业务类型：MINUTES_AUDIO */
    private String bizType;
    /** 业务主键：会议 id */
    private Long bizId;
    private String objectKey;
    private String originalName;
    private Long sizeBytes;
    private String contentType;
    /** 0 正常 / 10 已删除 */
    private Integer status;
    private Long uploadedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
