package com.smt.meeting.service;

import cn.hutool.core.util.StrUtil;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.common.result.PageResult;
import com.smt.common.mq.MeetingDomainEvent;
import com.smt.common.util.PageBounds;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingApproval;
import com.smt.meeting.enums.MeetingEventEnum;
import com.smt.meeting.enums.MeetingStatusEnum;
import com.smt.meeting.lock.MeetingLockHelper;
import com.smt.meeting.mapper.MeetingApprovalMapper;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingRoomMapper;
import com.smt.meeting.statemachine.MeetingStateMachine;
import com.smt.meeting.vo.ApprovalHistoryVO;
import com.smt.meeting.vo.ApprovalResultVO;
import com.smt.meeting.vo.ApprovalVO;
import com.smt.meeting.vo.MeetingConflictVO;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * 审批服务（T3.1 待办/已办列表；T3.2 通过；T3.3 拒绝）
 *
 * 设计要点（docs/01 流程 2）：**"待审批不占用时段" + "审批时复检"** 是一对——
 * 10 待审批不占时段（R-4），因此审批通过是"从 0 到 1 占用某时段"的动作，
 * 与"新建预约"是同一种竞态：只锁会议行挡不住并发的 create，必须复用同一套防护
 * （房间锁 → 会议室行锁 → 锁内复检）。审批失败（50001）**不是异常，是设计内的业务分支**：
 * 申请人改期重送即可，前端弹冲突详情。
 */
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private static final String ACTION_PASS = "PASS";
    private static final String ACTION_REJECT = "REJECT";

    private final MeetingMapper meetingMapper;
    private final MeetingApprovalMapper approvalMapper;
    private final MeetingRoomMapper meetingRoomMapper;
    private final ConflictChecker conflictChecker;
    private final MeetingStateMachine stateMachine;
    private final MeetingLockHelper lockHelper;
    private final TransactionTemplate transactionTemplate;
    /** 领域事件发布（T4.1）：事务内发布，AFTER_COMMIT 监听器转投 MQ */
    private final ApplicationEventPublisher eventPublisher;

    // ---------- T3.1 列表 ----------

    /** 待审批列表：全量 status=10（审批人不分部门/会议室范围，见 docs/04 3.2 权限列） */
    public PageResult<ApprovalVO> pending(Integer page, Integer size) {
        PageBounds bounds = PageBounds.of(page, size);
        long total = meetingMapper.countPending();
        List<ApprovalVO> list = total == 0 ? List.of()
                : meetingMapper.selectPendingPage(bounds.offset(), bounds.size());
        return PageResult.of(list, total, bounds.page(), bounds.size());
    }

    /** 已处理列表：只给"我处理过的"（审批人视角的已办箱） */
    public PageResult<ApprovalHistoryVO> history(Integer page, Integer size, Long approverId) {
        PageBounds bounds = PageBounds.of(page, size);
        long total = approvalMapper.countHistory(approverId);
        List<ApprovalHistoryVO> list = total == 0 ? List.of()
                : approvalMapper.selectHistoryPage(approverId, bounds.offset(), bounds.size());
        return PageResult.of(list, total, bounds.page(), bounds.size());
    }

    // ---------- T3.2 通过 ----------

    /**
     * 审批通过：锁（房间锁 ⊇ 事务）→ 会议室行锁 → 会议行锁 → 复检冲突 → 10→20 → 写审批记录。
     *
     * 为什么审批也要抢**房间锁**（而不只是两把行锁）：通过会把 10 → 20，即"从今往后这个时段被占用"。
     * 并发的"新建预约"走的是同一把房间锁，只靠会议行锁挡不住它——它锁的是**另一行**（新会议还没插入）。
     *
     * @throws BusinessException 40003 会议不存在 / 50002 已被处理 / 50001 复检冲突（data 带 conflicts）
     */
    public ApprovalResultVO pass(Long meetingId, String comment, Long approverId) {
        Meeting meeting = loadMeeting(meetingId);

        return lockHelper.executeWithLock(meeting.getRoomId(), meeting.getStartTime(), meeting.getEndTime(), () ->
                transactionTemplate.execute(tx -> {
                    // 锁序固定「先会议室行 → 后会议行」（CLAUDE.md 红线 #10）：反过来会与改期交叉持锁死锁
                    meetingRoomMapper.selectIdForUpdate(meeting.getRoomId());

                    // 再锁会议行：把同一条会议上的并发审批（两个人同时点通过）串行化
                    Meeting locked = meetingMapper.selectByIdForUpdate(meetingId);
                    if (locked == null) {
                        throw new BusinessException(ErrorCode.MEETING_NOT_FOUND);
                    }
                    if (locked.getStatus() == null
                            || locked.getStatus() != MeetingStatusEnum.PENDING_APPROVAL.getCode()) {
                        // 已被别人处理（通过/拒绝/申请人自己取消），或压根不在待审批态
                        throw new BusinessException(ErrorCode.APPROVAL_ALREADY_HANDLED);
                    }

                    // 复检：待审批期间时段可能已被抢（R-4：10 不占时段，所以这步不可省）
                    List<MeetingConflictVO> conflicts = conflictChecker.findConflicts(
                            locked.getRoomId(), locked.getStartTime(), locked.getEndTime(), meetingId);
                    if (!conflicts.isEmpty()) {
                        throw new BusinessException(ErrorCode.APPROVAL_CONFLICT, Map.of("conflicts", conflicts));
                    }

                    stateMachine.transition(meetingId, MeetingEventEnum.APPROVE, approverId, comment);
                    insertApproval(meetingId, approverId, ACTION_PASS, comment);
                    // 领域事件（T4.1）：通知申请人/参会人"通过了"，事务外由 AFTER_COMMIT 监听器转投 MQ
                    eventPublisher.publishEvent(MeetingDomainEvent.approved(
                            meetingId, locked.getTitle(), locked.getRoomId(), locked.getOrganiserId(),
                            locked.getStartTime(), locked.getEndTime(), approverId, comment));
                    return new ApprovalResultVO(meetingId, MeetingStatusEnum.RESERVED.getCode());
                }));
    }

    // ---------- T3.3 拒绝 ----------

    /**
     * 审批拒绝：comment 必填（D-3）→ 锁会议行 → 10→60 → 写审批记录。
     *
     * 不抢房间锁：拒绝**不改变时段占用**（10 与 60 都不占时段），没有可竞争的时段资源。
     * 只锁会议行就够（防与"通过"并发——通过那边会同时持会议室行与会议行，这里只等会议行，
     * 不构成环，不会死锁）。
     */
    public ApprovalResultVO reject(Long meetingId, String comment, Long approverId) {
        // 先做参数校验（快速失败，不必为一个注定失败的请求去抢锁/占事务）
        if (StrUtil.isBlank(comment)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "拒绝时必须填写理由");
        }

        return transactionTemplate.execute(tx -> {
            Meeting locked = meetingMapper.selectByIdForUpdate(meetingId);
            if (locked == null) {
                throw new BusinessException(ErrorCode.MEETING_NOT_FOUND);
            }
            if (locked.getStatus() == null
                    || locked.getStatus() != MeetingStatusEnum.PENDING_APPROVAL.getCode()) {
                throw new BusinessException(ErrorCode.APPROVAL_ALREADY_HANDLED);
            }

            stateMachine.transition(meetingId, MeetingEventEnum.REJECT, approverId, comment);
            insertApproval(meetingId, approverId, ACTION_REJECT, comment);
            eventPublisher.publishEvent(MeetingDomainEvent.rejected(
                    meetingId, locked.getTitle(), locked.getRoomId(), locked.getOrganiserId(),
                    locked.getStartTime(), locked.getEndTime(), approverId, comment));
            return new ApprovalResultVO(meetingId, MeetingStatusEnum.REJECTED.getCode());
        });
    }

    // ---------- 内部 ----------

    private Meeting loadMeeting(Long meetingId) {
        Meeting meeting = meetingMapper.selectById(meetingId);
        if (meeting == null) {
            throw new BusinessException(ErrorCode.MEETING_NOT_FOUND);
        }
        return meeting;
    }

    /** 写审批记录（必须在审批事务内调用：状态迁移与审批留痕要么都成、要么都不成） */
    private void insertApproval(Long meetingId, Long approverId, String action, String comment) {
        MeetingApproval approval = new MeetingApproval();
        approval.setMeetingId(meetingId);
        approval.setApproverId(approverId);
        approval.setAction(action);
        approval.setComment(comment);
        approvalMapper.insert(approval);
    }
}
