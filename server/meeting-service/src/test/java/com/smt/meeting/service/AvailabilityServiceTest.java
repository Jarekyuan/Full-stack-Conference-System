package com.smt.meeting.service;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingParticipantMapper;
import com.smt.meeting.vo.RoomCalendarVO;
import com.smt.meeting.vo.RoomCalendarsVO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * T6.3 评审 R14.8：批量日历的“逐房间容错”与去重。
 *
 * <p>集成脚本无法稳定造出“单个房间失败”（DB/Redis 正常时 calendar 不会抛），
 * 这里用 spy 精确控制：room 1 抛异常、room 2 正常，断言 room 1 进 failedRoomIds、
 * room 2 仍返回，且重复的 roomId 只查一次。
 */
class AvailabilityServiceTest {

    private static final LocalDate WEEK = LocalDate.of(2026, 10, 19);
    private static final Long USER_ID = 4L;

    private AvailabilityService newService() {
        return spy(new AvailabilityService(
                mock(ConflictChecker.class),
                mock(MeetingMapper.class),
                mock(MeetingParticipantMapper.class),
                mock(StringRedisTemplate.class),
                mock(RedissonClient.class),
                new ObjectMapper(),
                mock(CalendarCacheEvictor.class)));
    }

    private RoomCalendarVO calendarOf(Long roomId) {
        RoomCalendarVO vo = new RoomCalendarVO();
        vo.setRoomId(roomId);
        vo.setWeekStart(WEEK);
        vo.setWeekEnd(WEEK.plusDays(6));
        vo.setDays(java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> new RoomCalendarVO.Day(WEEK.plusDays(i), List.of()))
                .toList());
        return vo;
    }

    @Test
    void calendarsShouldIsolateFailedRoomAndDedup() {
        AvailabilityService service = newService();
        doThrow(new RuntimeException("模拟单房间回源失败")).when(service).calendar(eq(1L), any(), any());
        doReturn(calendarOf(2L)).when(service).calendar(eq(2L), any(), any());

        RoomCalendarsVO result = service.calendars(List.of(1L, 2L, 1L), WEEK, USER_ID);

        assertThat(result.items()).extracting(RoomCalendarVO::getRoomId).containsExactly(2L);
        assertThat(result.failedRoomIds()).containsExactly(1L);
        assertThat(result.weekStart()).isEqualTo(WEEK);
        assertThat(result.weekEnd()).isEqualTo(WEEK.plusDays(6));
        // 重复 roomId 去重：room 1、room 2 各只调一次
        verify(service, times(1)).calendar(eq(1L), any(), any());
        verify(service, times(1)).calendar(eq(2L), any(), any());
    }
}
