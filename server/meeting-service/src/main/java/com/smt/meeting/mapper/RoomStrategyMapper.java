package com.smt.meeting.mapper;

import com.smt.meeting.entity.RoomStrategy;
import org.apache.ibatis.annotations.Param;

/**
 * 会议室策略 Mapper（SQL 手写 XML：resources/mapper/RoomStrategyMapper.xml）
 */
public interface RoomStrategyMapper {

    /** 按会议室查询策略（uk_room 唯一，最多一条） */
    RoomStrategy selectByRoomId(@Param("roomId") Long roomId);

    /** 新增（新会议室默认策略） */
    int insert(RoomStrategy strategy);

    /**
     * 原子 upsert（按 room_id 命中唯一索引 uk_room：存在则全量更新，不存在则插入）。
     * 替代原"先 UPDATE 影响 0 行再 INSERT"的非原子写法（并发下会撞唯一键，见评审 P1）。
     */
    int upsertByRoomId(RoomStrategy strategy);

    /** 按会议室删除（删除会议室时级联清理） */
    int deleteByRoomId(@Param("roomId") Long roomId);
}
