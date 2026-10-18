package com.smt.meeting.mapper;

import com.smt.meeting.entity.FileUploadSession;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分片上传会话 Mapper（SQL 手写 XML：resources/mapper/FileUploadSessionMapper.xml）
 */
public interface FileUploadSessionMapper {

    /** 新增上传会话（自增 id 回填到实体） */
    int insert(FileUploadSession session);

    /** 按 uploadId 查询（命中 uk_upload_id） */
    FileUploadSession selectByUploadId(@Param("uploadId") String uploadId);

    /** 完成上传：仅进行中(0)的会话可迁移，并回填 file_object.id */
    int markCompleted(@Param("uploadId") String uploadId, @Param("fileId") Long fileId);

    /** 状态迁移：仅进行中(0)的会话可迁移（中止/过期） */
    int markStatus(@Param("uploadId") String uploadId, @Param("status") int status);

    /** 扫描已过期但仍进行中的会话（命中 idx_status_expires） */
    List<FileUploadSession> selectExpired(@Param("now") LocalDateTime now,
                                          @Param("limit") int limit);
}
