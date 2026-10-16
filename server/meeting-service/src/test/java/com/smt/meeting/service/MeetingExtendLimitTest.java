package com.smt.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.RoomStrategy;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.RoomStrategyMapper;
import com.smt.meeting.vo.ExtendLimitVO;

/**
 * T4.10 补齐：延长上限计算（T3.4 / R-15）的三种封顶场景。
 *
 * <p>规则：`min(下一场开始 − 锁定缓冲, 当日 24:00) − 当前结束`。三种 reason 是给前端**直接展示**的
 * （docs/04 4.6 写明"不让前端算业务规则"），所以文案也得钉住——它是接口契约的一部分。
 */
class MeetingExtendLimitTest {

    private static final Long MEETING_ID = 100L;
    private static final Long ROOM_ID = 6L;

    private MeetingMapper meetingMapper;
    private RoomStrategyMapper strategyMapper;
    private MeetingReserveService service;

    @BeforeEach
    void setUp() {
        meetingMapper = mock(MeetingMapper.class);
        strategyMapper = mock(RoomStrategyMapper.class);
        // 只测"上限计算"这条只读路径：其余依赖传 null（它不会走到）
        service = new MeetingReserveService(meetingMapper, null, strategyMapper, null, null, null, null, null, null);
    }

    private Meeting ongoing(LocalDateTime end) {
        Meeting m = new Meeting();
        m.setId(MEETING_ID);
        m.setRoomId(ROOM_ID);
        m.setStatus(30);
        m.setStartTime(end.minusHours(1));
        m.setEndTime(end);
        return m;
    }

    private RoomStrategy strategy(int buffer) {
        RoomStrategy s = new RoomStrategy();
        s.setRoomId(ROOM_ID);
        s.setBufferMinutes(buffer);
        return s;
    }

    @Test
    void limitedByNextMeetingWithBuffer() {
        LocalDateTime end = LocalDateTime.of(2026, 11, 20, 15, 0);
        when(meetingMapper.selectById(MEETING_ID)).thenReturn(ongoing(end));
        when(strategyMapper.selectByRoomId(ROOM_ID)).thenReturn(strategy(15));
        // 下一场 15:45 开始，含 15 分钟缓冲 → 只能延到 15:30
        when(meetingMapper.selectNextMeetingStart(any(), any(), anyLong()))
                .thenReturn(LocalDateTime.of(2026, 11, 20, 15, 45));

        ExtendLimitVO vo = service.extendLimit(MEETING_ID);

        assertThat(vo.maxDelayMinutes()).isEqualTo(30);
        assertThat(vo.reason()).isEqualTo("下一场会议 15:45 开始（含 15 分钟锁定缓冲）");
    }

    @Test
    void limitedByDayEndWhenNoNextMeeting() {
        LocalDateTime end = LocalDateTime.of(2026, 11, 20, 22, 0);
        when(meetingMapper.selectById(MEETING_ID)).thenReturn(ongoing(end));
        when(strategyMapper.selectByRoomId(ROOM_ID)).thenReturn(strategy(0));
        when(meetingMapper.selectNextMeetingStart(any(), any(), anyLong())).thenReturn(null);

        ExtendLimitVO vo = service.extendLimit(MEETING_ID);

        assertThat(vo.maxDelayMinutes()).isEqualTo(120); // 22:00 → 次日 00:00
        assertThat(vo.reason()).isEqualTo("当日无后续会议，最多可延至 24:00");
    }

    @Test
    void nextMeetingBeyondTodayStillCappedByDayEnd() {
        LocalDateTime end = LocalDateTime.of(2026, 11, 20, 22, 0);
        when(meetingMapper.selectById(MEETING_ID)).thenReturn(ongoing(end));
        when(strategyMapper.selectByRoomId(ROOM_ID)).thenReturn(strategy(0));
        // 下一场在次日 → 仍按当日 24:00 封顶（跨天延长会让"按天加锁"的约定失效）
        when(meetingMapper.selectNextMeetingStart(any(), any(), anyLong()))
                .thenReturn(LocalDateTime.of(2026, 11, 21, 9, 0));

        ExtendLimitVO vo = service.extendLimit(MEETING_ID);

        assertThat(vo.maxDelayMinutes()).isEqualTo(120);
        assertThat(vo.reason()).isEqualTo("下一场会议不在当日，最多可延至 24:00");
    }
}
