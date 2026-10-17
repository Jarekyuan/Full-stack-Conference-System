package com.smt.meeting.mapper;

import com.smt.meeting.entity.MeetingParticipant;
import com.smt.meeting.vo.MeetingDetailVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会议参会人 Mapper（SQL 手写 XML：resources/mapper/MeetingParticipantMapper.xml）
 */
public interface MeetingParticipantMapper {

    /** 批量新增（<foreach> 生成多值 INSERT） */
    int batchInsert(@Param("list") List<MeetingParticipant> list);

    /** 按会议查询参会人（实体；内部用） */
    List<MeetingParticipant> selectByMeetingId(@Param("meetingId") Long meetingId);

    /** 参会人明细（JOIN sys_user 直出姓名/邮箱；T2.8 详情用） */
    List<MeetingDetailVO.ParticipantVO> selectDetailByMeetingId(@Param("meetingId") Long meetingId);

    /**
     * 这批会议里当前用户是参会人的 meetingId（T6.1 日历“邀请我的”）。
     * 命中 idx_user；返回值用于在**缓存读出后**给 VO 打 invited 标记（用户相关，不能进共享缓存）。
     */
    List<Long> selectInvitedMeetingIds(@Param("userId") Long userId,
                                       @Param("meetingIds") List<Long> meetingIds);
}
