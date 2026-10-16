package com.smt.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.smt.meeting.entity.Meeting;
import com.smt.meeting.enums.MeetingEventEnum;
import com.smt.meeting.lock.MeetingLockHelper;
import com.smt.meeting.mapper.MeetingApprovalMapper;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingRoomMapper;
import com.smt.meeting.statemachine.MeetingStateMachine;
import com.smt.meeting.vo.MeetingConflictVO;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;

/**
 * T4.10 补齐：审批通过（T3.2）的三条分支。
 *
 * <p>为什么值得单测：**"待审批不占时段"意味着审批通过是"从 0 到 1 占用时段"的动作**，
 * 复检（50001）与"已被别人处理"（50002）是最容易写反的两条分支，
 * 而它们只在并发/抢占场景下才出现——脚本能覆盖一部分，但分支本身要钉住。
 */
class ApprovalServiceTest {

    private static final Long MEETING_ID = 100L;
    private static final Long APPROVER_ID = 9L;

    private MeetingMapper meetingMapper;
    private MeetingApprovalMapper approvalMapper;
    private MeetingRoomMapper roomMapper;
    private ConflictChecker conflictChecker;
    private MeetingStateMachine stateMachine;
    private ApplicationEventPublisher eventPublisher;
    private ApprovalService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        meetingMapper = mock(MeetingMapper.class);
        approvalMapper = mock(MeetingApprovalMapper.class);
        roomMapper = mock(MeetingRoomMapper.class);
        conflictChecker = mock(ConflictChecker.class);
        stateMachine = mock(MeetingStateMachine.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        MeetingLockHelper lockHelper = mock(MeetingLockHelper.class);
        when(lockHelper.executeWithLock(any(), any(), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());

        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));

        service = new ApprovalService(meetingMapper, approvalMapper, roomMapper,
                conflictChecker, stateMachine, lockHelper, tx, eventPublisher);
    }

    private Meeting meeting(int status) {
        Meeting m = new Meeting();
        m.setId(MEETING_ID);
        m.setTitle("产品评审会");
        m.setRoomId(6L);
        m.setOrganiserId(4L);
        m.setStatus(status);
        m.setStartTime(LocalDateTime.of(2026, 11, 20, 10, 0));
        m.setEndTime(LocalDateTime.of(2026, 11, 20, 11, 0));
        return m;
    }

    @Test
    void recheckConflictShouldFailWith50001AndNotTransition() {
        Meeting m = meeting(10);
        when(meetingMapper.selectById(MEETING_ID)).thenReturn(m);
        when(meetingMapper.selectByIdForUpdate(MEETING_ID)).thenReturn(m);
        when(conflictChecker.findConflicts(any(), any(), any(), anyLong()))
                .thenReturn(List.of(new MeetingConflictVO()));

        assertThatThrownBy(() -> service.pass(MEETING_ID, "同意", APPROVER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.APPROVAL_CONFLICT.getCode());
        // 复检没过就**不能**动状态：否则时段被抢占的会议会被批成"已预约"，两场会叠在一起
        verify(stateMachine, never()).transition(any(), any(), any(), anyString());
        verify(approvalMapper, never()).insert(any());
    }

    @Test
    void alreadyHandledMeetingShouldFailWith50002() {
        Meeting m = meeting(20); // 已经被别人批过了
        when(meetingMapper.selectById(MEETING_ID)).thenReturn(m);
        when(meetingMapper.selectByIdForUpdate(MEETING_ID)).thenReturn(m);

        assertThatThrownBy(() -> service.pass(MEETING_ID, "同意", APPROVER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.APPROVAL_ALREADY_HANDLED.getCode());
        verify(conflictChecker, never()).findConflicts(any(), any(), any(), any());
    }

    @Test
    void happyPathShouldTransitionAndRecordApproval() {
        Meeting m = meeting(10);
        when(meetingMapper.selectById(MEETING_ID)).thenReturn(m);
        when(meetingMapper.selectByIdForUpdate(MEETING_ID)).thenReturn(m);
        when(conflictChecker.findConflicts(any(), any(), any(), anyLong())).thenReturn(List.of());

        var result = service.pass(MEETING_ID, "同意", APPROVER_ID);

        assertThat(result.status()).isEqualTo(20);
        verify(stateMachine).transition(eq(MEETING_ID), eq(MeetingEventEnum.APPROVE), eq(APPROVER_ID), eq("同意"));
        verify(approvalMapper).insert(any());
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void rejectWithoutCommentShouldFailWith10001() {
        assertThatThrownBy(() -> service.reject(MEETING_ID, "  ", APPROVER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }
}
