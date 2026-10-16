package com.smt.meeting.mapper;

import com.smt.meeting.entity.MeetingRecurring;

/**
 * 周期会议规则 Mapper（SQL 手写 XML：resources/mapper/MeetingRecurringMapper.xml）
 */
public interface MeetingRecurringMapper {

    /** 新增规则（自增回填 id，供场次回指） */
    int insert(MeetingRecurring recurring);

    /** 按 id 查规则（系列取消/展示时用） */
    MeetingRecurring selectById(Long id);
}
