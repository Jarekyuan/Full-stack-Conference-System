package com.smt.meeting.mapper;

import com.smt.meeting.entity.MeetingStatusLog;
import com.smt.meeting.vo.MeetingDetailVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会议状态日志 Mapper（SQL 手写 XML：resources/mapper/MeetingStatusLogMapper.xml）
 */
public interface MeetingStatusLogMapper {

    /** 追加一条状态变更日志（状态机在同一事务内调用；created_at 走数据库默认值） */
    int insert(MeetingStatusLog log);

    /** 按会议查询状态变更实体（升序） */
    List<MeetingStatusLog> selectByMeetingId(@Param("meetingId") Long meetingId);

    /** 状态时间线（JOIN sys_user 直出操作人姓名；T2.8 详情用，operator 为空 = 系统任务） */
    List<MeetingDetailVO.StatusLogVO> selectTimeline(@Param("meetingId") Long meetingId);
}
