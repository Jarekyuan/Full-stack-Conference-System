package com.smt.meeting.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.dto.MeetingCreateDTO;
import com.smt.meeting.dto.MeetingUpdateDTO;
import com.smt.meeting.dto.RecurringCreateDTO;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingRoom;
import com.smt.meeting.entity.RoomStrategy;
import com.smt.meeting.lock.MeetingLockHelper;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingParticipantMapper;
import com.smt.meeting.mapper.MeetingRecurringMapper;
import com.smt.meeting.mapper.MeetingRoomMapper;
import com.smt.meeting.mapper.RoomStrategyMapper;
import com.smt.meeting.statemachine.MeetingStateMachine;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T6.2 评审 R12：锁内复查维护状态必须真的被执行。
 *
 * <p>集成脚本只能构造“请求发出前房间已是维护中”，命中的是抢锁前快检；
 * 这个测试让 mock 的 `selectById` **第一次返回可用、第二次返回维护**，
 * 精确命中锁内复查（第二次调用发生在 `selectIdForUpdate` 之后）。
 */
class MaintenanceRecheckTest {

    private static final Long ROOM_ID = 1L;
    private static final Long MEETING_ID = 100L;
    private static final Long OPERATOR_ID = 4L;

    private MeetingMapper meetingMapper;
    private MeetingRoomMapper roomMapper;
    private RoomStrategyMapper strategyMapper;
    private MeetingParticipantMapper participantMapper;
    private ConflictChecker conflictChecker;
    private MeetingStateMachine stateMachine;
    private MeetingLockHelper lockHelper;
    private TransactionTemplate transactionTemplate;
    private ApplicationEventPublisher eventPublisher;
    private CalendarCacheEvictor cacheEvictor;

    private MeetingReserveService reserveService;
    private RecurringService recurringService;

    @BeforeEach
    void setUp() {
        meetingMapper = mock(MeetingMapper.class);
        roomMapper = mock(MeetingRoomMapper.class);
        strategyMapper = mock(RoomStrategyMapper.class);
        participantMapper = mock(MeetingParticipantMapper.class);
        conflictChecker = mock(ConflictChecker.class);
        stateMachine = mock(MeetingStateMachine.class);
        lockHelper = mock(MeetingLockHelper.class);
        transactionTemplate = mock(TransactionTemplate.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        cacheEvictor = mock(CalendarCacheEvictor.class);

        reserveService = new MeetingReserveService(meetingMapper, roomMapper, strategyMapper,
                participantMapper, conflictChecker, stateMachine, lockHelper, transactionTemplate,
                eventPublisher, cacheEvictor);
        recurringService = new RecurringService(meetingMapper, mock(MeetingRecurringMapper.class),
                roomMapper, strategyMapper, conflictChecker, stateMachine, reserveService,
                lockHelper, transactionTemplate, cacheEvictor, eventPublisher);

        // 让锁与事务直接执行回调（不启动 Spring/Redis）
        when(lockHelper.executeWithLock(any(), any(), any(), any())).thenAnswer(inv ->
                ((Supplier<?>) inv.getArgument(3)).get());
        when(lockHelper.executeWithLockOnDates(any(), any(), any())).thenAnswer(inv ->
                ((Supplier<?>) inv.getArgument(2)).get());
        Mockito.doAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());

        RoomStrategy strategy = new RoomStrategy();
        strategy.setRoomId(ROOM_ID);
        strategy.setNeedApproval(0);
        strategy.setAdvanceDays(30);
        strategy.setMaxDurationMinutes(240);
        strategy.setAllowRecurring(1);
        when(strategyMapper.selectByRoomId(ROOM_ID)).thenReturn(strategy);
    }

    private MeetingRoom room(int status) {
        MeetingRoom room = new MeetingRoom();
        room.setId(ROOM_ID);
        room.setName("测试厅");
        room.setStatus(status);
        return room;
    }

    private LocalDateTime futureStart() {
        return LocalDate.now().plusDays(7).atTime(LocalTime.of(10, 0));
    }

    @Test
    void createShouldRejectWhenRoomBecomesMaintenanceAfterQuickCheck() {
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(0), room(1));

        MeetingCreateDTO dto = new MeetingCreateDTO();
        dto.setTitle("锁内维护复查");
        dto.setRoomId(ROOM_ID);
        dto.setStartTime(futureStart());
        dto.setEndTime(futureStart().plusHours(1));

        assertThatThrownBy(() -> reserveService.create(dto, OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_MAINTENANCE.getCode());
        verify(meetingMapper, never()).insert(any());
    }

    @Test
    void updateShouldRejectWhenTargetRoomBecomesMaintenanceAfterQuickCheck() {
        Meeting meeting = new Meeting();
        meeting.setId(MEETING_ID);
        meeting.setRoomId(ROOM_ID);
        meeting.setOrganiserId(OPERATOR_ID);
        meeting.setStatus(20);
        meeting.setStartTime(futureStart());
        meeting.setEndTime(futureStart().plusHours(1));
        when(meetingMapper.selectById(MEETING_ID)).thenReturn(meeting);
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(0), room(1));

        MeetingUpdateDTO dto = new MeetingUpdateDTO();
        dto.setTitle("锁内维护复查");
        dto.setRoomId(ROOM_ID);
        dto.setStartTime(futureStart().plusHours(2));
        dto.setEndTime(futureStart().plusHours(3));
        dto.setVersion(0);

        assertThatThrownBy(() -> reserveService.update(MEETING_ID, dto, OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_MAINTENANCE.getCode());
        verify(meetingMapper, never()).updateWithVersion(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void extendShouldRejectWhenRoomIsMaintenance() {
        // O19：延长也是新增占用，维护中的房间必须在锁内拒绝
        Meeting meeting = new Meeting();
        meeting.setId(MEETING_ID);
        meeting.setRoomId(ROOM_ID);
        meeting.setOrganiserId(OPERATOR_ID);
        meeting.setStatus(30);
        meeting.setStartTime(futureStart());
        meeting.setEndTime(futureStart().plusHours(1));
        when(meetingMapper.selectById(MEETING_ID)).thenReturn(meeting);
        when(meetingMapper.selectByIdForUpdate(MEETING_ID)).thenReturn(meeting);
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(1));

        assertThatThrownBy(() -> reserveService.extend(MEETING_ID, 10, OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_MAINTENANCE.getCode());
        verify(meetingMapper, never()).updateEndTime(any(), any(), anyInt(), anyInt());
    }

    @Test
    void recurringCreateShouldRejectWhenRoomBecomesMaintenanceAfterQuickCheck() {
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(0), room(1));

        LocalDate day = LocalDate.now().plusDays(7);
        RecurringCreateDTO dto = new RecurringCreateDTO();
        dto.setRoomId(ROOM_ID);
        dto.setTitle("锁内维护复查-周期");
        dto.setDayOfWeek(day.getDayOfWeek().getValue());
        dto.setStartMinutes(600);
        dto.setDurationMinutes(60);
        dto.setStartDate(day);
        dto.setEndDate(day);

        assertThatThrownBy(() -> recurringService.create(dto, OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_MAINTENANCE.getCode());
    }
}
