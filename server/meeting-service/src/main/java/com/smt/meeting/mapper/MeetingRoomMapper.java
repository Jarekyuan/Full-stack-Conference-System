package com.smt.meeting.mapper;

import com.smt.meeting.entity.MeetingRoom;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会议室 Mapper（SQL 手写 XML：resources/mapper/MeetingRoomMapper.xml）
 */
public interface MeetingRoomMapper {

    /** 全部会议室（按 id 排序；images/deviceTags 经 JsonTypeHandler 解析） */
    List<MeetingRoom> selectAll();

    /** 按主键查询 */
    MeetingRoom selectById(@Param("id") Long id);
}
