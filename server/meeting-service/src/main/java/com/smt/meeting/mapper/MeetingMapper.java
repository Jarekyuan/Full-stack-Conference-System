package com.smt.meeting.mapper;

import com.smt.meeting.dto.MeetingQueryParam;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.vo.ApprovalVO;
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
     * 事务内锁住会议行（`SELECT ... FOR UPDATE`，T3.2/T3.3 审批用）。
     * 用途：把"复检 → 状态迁移 → 写审批记录"串行化，防**双人同审**（两个审批人同时点通过）。
     * ⚠️ 调用方必须保证：① 在事务内；② 若同时要锁会议室行，**先会议室行、后会议行**（CLAUDE.md 红线 #10）。
     */
    Meeting selectByIdForUpdate(@Param("id") Long id);

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

    /**
     * 待审批列表（T3.1）：status=10 的会议 + 会议室名/组织人名/用途备注。
     * 与列表 VO 分开的原因：`MeetingVO` 按场景裁剪掉了 remark，而审批人正是靠它做判断（见 docs/04 3.2）。
     */
    List<ApprovalVO> selectPendingPage(@Param("offset") int offset, @Param("size") int size);

    /** 待审批总数（分页配套 COUNT） */
    long countPending();

    /**
     * 乐观锁更新（T2.10）：只改非状态字段 + version 自增；
     * WHERE version=#{version} 校验并发——影响 0 行 = 已被他人修改，调用方按 40002 提示重试。
     * status 变更（R-14 回退到待审批）单独走状态机，保证日志一致。
     *
     * 【为什么还要校 status】version 只在"改会议"时自增，而**取消走的是状态机 CAS（只改 status）**，
     * version 不动。于是"读会议 → 抢锁/等行锁（秒级窗口）→ 更新"期间若被取消，
     * `WHERE version=?` 仍会命中，出现"已取消的会议被改期"（评审 C4）。
     * 加上 expectedStatus 后，这类交叉写会以影响 0 行收场 → 调用方按 40002 提示刷新。
     */
    int updateWithVersion(@Param("id") Long id,
                          @Param("title") String title,
                          @Param("roomId") Long roomId,
                          @Param("startTime") LocalDateTime startTime,
                          @Param("endTime") LocalDateTime endTime,
                          @Param("remark") String remark,
                          @Param("version") int version,
                          @Param("expectedStatus") int expectedStatus);
}
