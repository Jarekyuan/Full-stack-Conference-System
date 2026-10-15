package com.smt.meeting.mapper;

import com.smt.meeting.entity.MeetingApproval;
import com.smt.meeting.vo.ApprovalHistoryVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 审批记录 Mapper（SQL 手写 XML：resources/mapper/MeetingApprovalMapper.xml）
 */
public interface MeetingApprovalMapper {

    /** 追加一条审批记录（自增回填 id） */
    int insert(MeetingApproval approval);

    /** 某会议的审批历史（按时间倒序；排障/审计用） */
    List<MeetingApproval> selectByMeetingId(@Param("meetingId") Long meetingId);

    /** 当前审批人处理过的记录（分页，JOIN 会议与相关人直出展示字段） */
    List<ApprovalHistoryVO> selectHistoryPage(@Param("approverId") Long approverId,
                                              @Param("offset") int offset,
                                              @Param("size") int size);

    /** 当前审批人处理过的记录总数（分页配套 COUNT） */
    long countHistory(@Param("approverId") Long approverId);
}
