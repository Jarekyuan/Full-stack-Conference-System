package com.smt.meeting.mapper;

import com.smt.meeting.entity.MeetingStatusLog;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会议状态日志 Mapper（SQL 手写 XML：resources/mapper/MeetingStatusLogMapper.xml）
 */
public interface MeetingStatusLogMapper {

    /** 追加一条状态变更日志（状态机在同一事务内调用；created_at 走数据库默认值） */
    int insert(MeetingStatusLog log);

    /** 按会议查询状态时间线（升序，T2.8 详情页用） */
    List<MeetingStatusLog> selectByMeetingId(@Param("meetingId") Long meetingId);
}
