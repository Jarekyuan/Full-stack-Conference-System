package com.smt.meeting.mapper;

import com.smt.meeting.entity.Meeting;
import org.apache.ibatis.annotations.Param;

/**
 * 会议主表 Mapper（SQL 手写 XML：resources/mapper/MeetingMapper.xml）
 */
public interface MeetingMapper {

    /** 按主键查询（状态机迁移前校验用） */
    Meeting selectById(@Param("id") Long id);

    /**
     * CAS 更新状态：仅当前状态等于 fromStatus 时更新，返回影响行数。
     * 状态机的并发防线——影响 0 行说明状态已被并发变更，调用方按 40002 处理。
     */
    int updateStatus(@Param("id") Long id,
                     @Param("fromStatus") int fromStatus,
                     @Param("toStatus") int toStatus);

    /** 新增会议（T2.4 创建预约用；status 由状态机 initialStatus 决定后落库） */
    int insert(Meeting meeting);
}
