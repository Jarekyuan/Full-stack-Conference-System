package com.smt.meeting.mapper;

import com.smt.meeting.entity.RoomGroup;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会议室分组 Mapper（SQL 手写 XML：resources/mapper/RoomGroupMapper.xml）
 */
public interface RoomGroupMapper {

    /** 全部分组（按 sort_order 排序） */
    List<RoomGroup> selectAll();

    /** 按主键查询（会议室保存时校验 group_id 不是悬空引用） */
    RoomGroup selectById(@Param("id") Long id);
}
