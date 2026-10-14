package com.smt.meeting.mapper;

import com.smt.meeting.entity.RoomStrategy;
import org.apache.ibatis.annotations.Param;

/**
 * 会议室策略 Mapper（SQL 手写 XML：resources/mapper/RoomStrategyMapper.xml）
 */
public interface RoomStrategyMapper {

    /** 按会议室查询策略（uk_room 唯一，最多一条） */
    RoomStrategy selectByRoomId(@Param("roomId") Long roomId);
}
