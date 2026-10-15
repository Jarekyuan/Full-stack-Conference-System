package com.smt.meeting.mapper;

import com.smt.meeting.dto.MeetingQueryParam;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.vo.CalendarMeetingVO;
import com.smt.meeting.vo.MeetingConflictVO;
import com.smt.meeting.vo.MeetingDetailVO;
import com.smt.meeting.vo.MeetingVO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 冲突检测核心查询（对照 docs/03 4.1）：扫描区间 [scanStart, scanEnd) 内该会议室的占用会议。
     * 调用方（ConflictChecker）负责按锁定缓冲扩展扫描区间；此处只做纯区间判重。
     * 占用状态：20 已预约 / 30 进行中（R-4：10 待审批不占用）。
     */
    List<MeetingConflictVO> selectConflicts(@Param("roomId") Long roomId,
                                            @Param("scanStart") LocalDateTime scanStart,
                                            @Param("scanEnd") LocalDateTime scanEnd,
                                            @Param("excludeMeetingId") Long excludeMeetingId);

    /**
     * 某会议室在 [rangeStart, rangeEnd) 内开始的会议（日历视图，T2.3）。
     * 只取有效状态 10/20/30（40/50/60 为历史态不展示）；按开始时间归属日期。
     */
    List<CalendarMeetingVO> selectByRoomAndRange(@Param("roomId") Long roomId,
                                                 @Param("rangeStart") LocalDateTime rangeStart,
                                                 @Param("rangeEnd") LocalDateTime rangeEnd);

    /**
     * 会议列表（T2.8）：动态条件 + 手写分页（LIMIT）。
     * scope=mine 时限定"我组织的 或 我参与的"（EXISTS 子查询）；
     * JOIN room/organiser/strategy 直出展示字段；participantCount 子查询统计。
     */
    List<MeetingVO> selectPage(MeetingQueryParam param);

    /** 列表总数（与 selectPage 同条件，独立 COUNT） */
    long countPage(MeetingQueryParam param);

    /** 会议详情（JOIN room/organiser；participants/logs 由 Service 装配） */
    MeetingDetailVO selectDetailById(@Param("id") Long id);

    /** 写取消信息（取消原因 + 取消时间；时间由 NOW() 落库）—T2.9 */
    int updateCancelInfo(@Param("id") Long id, @Param("reason") String reason);
}
