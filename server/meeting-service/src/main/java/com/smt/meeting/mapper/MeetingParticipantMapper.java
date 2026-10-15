package com.smt.meeting.mapper;

import com.smt.meeting.entity.MeetingParticipant;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会议参会人 Mapper（SQL 手写 XML：resources/mapper/MeetingParticipantMapper.xml）
 */
public interface MeetingParticipantMapper {

    /** 批量新增（内部 + 外部参会人一次落库，教学点：<foreach> 生成多值 INSERT） */
    int batchInsert(@Param("list") List<MeetingParticipant> list);

    /** 按会议查询参会人（T2.8 详情页用） */
    List<MeetingParticipant> selectByMeetingId(@Param("meetingId") Long meetingId);
}
