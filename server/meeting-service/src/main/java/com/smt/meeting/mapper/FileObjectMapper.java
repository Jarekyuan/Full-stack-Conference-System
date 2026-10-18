package com.smt.meeting.mapper;

import com.smt.meeting.entity.FileObject;
import org.apache.ibatis.annotations.Param;

/**
 * 文件对象元数据 Mapper（SQL 手写 XML：resources/mapper/FileObjectMapper.xml）
 */
public interface FileObjectMapper {

    /** 新增文件对象（自增 id 回填到实体） */
    int insert(FileObject fileObject);

    /** 按主键查询 */
    FileObject selectById(@Param("id") Long id);

    /** 按对象存储 key 查询（命中 uk_object_key） */
    FileObject selectByObjectKey(@Param("objectKey") String objectKey);

    /** 软删除：status 置 10（已删除） */
    int markDeleted(@Param("id") Long id);
}
