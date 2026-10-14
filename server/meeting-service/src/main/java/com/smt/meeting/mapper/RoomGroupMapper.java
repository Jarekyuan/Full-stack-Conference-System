package com.smt.meeting.mapper;

import com.smt.meeting.entity.RoomGroup;

import java.util.List;

/**
 * 会议室分组 Mapper（SQL 手写 XML：resources/mapper/RoomGroupMapper.xml）
 */
public interface RoomGroupMapper {

    /** 全部分组（按 sort_order 排序） */
    List<RoomGroup> selectAll();
}
