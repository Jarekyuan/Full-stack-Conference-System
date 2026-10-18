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

    /** 软删除 CAS：仅正常(0) → 已删除(10)，重复调用影响 0 行 */
    int markDeleted(@Param("id") Long id);
}
