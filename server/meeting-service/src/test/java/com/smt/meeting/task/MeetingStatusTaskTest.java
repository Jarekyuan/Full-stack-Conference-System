package com.smt.meeting.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.enums.MeetingEventEnum;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.statemachine.MeetingStateMachine;

/**
 * T4.10 补齐：定时任务的**批处理**与**失败隔离**（T3.5/T3.6）。
 *
 * <p>脚本（`verify-m3-scheduler.sh`）验的是"到点会流转、多实例只有一个在跑"，
 * 属于端到端；这里钉住的是三条容易在重构中被破坏的性质：
 * ① 一条失败不能影响同批其他条（否则一条脏数据能把整轮任务卡死）；
 * ② 抢不到任务锁时**什么都不做**（多实例互斥的核心）；
 * ③ 状态机的 BusinessException 是"跳过"（CAS 没命中/状态已变），**不是错误**。
 */
class MeetingStatusTaskTest {

    private MeetingMapper meetingMapper;
    private MeetingStateMachine stateMachine;
    private RLock lock;
    private MeetingStatusTask task;

    @BeforeEach
    void setUp() throws InterruptedException {
        meetingMapper = mock(MeetingMapper.class);
        stateMachine = mock(MeetingStateMachine.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        task = new MeetingStatusTask(meetingMapper, stateMachine, redissonClient);
    }

    @Test
    void shouldAdvanceEveryMeetingInBatch() {
        when(meetingMapper.selectIdsToStart(anyInt())).thenReturn(List.of(1L, 2L));
        when(meetingMapper.selectIdsToFinish(anyInt())).thenReturn(List.of(3L));

        task.advanceStatus();

        verify(stateMachine).transition(eq(1L), eq(MeetingEventEnum.START), any());
        verify(stateMachine).transition(eq(2L), eq(MeetingEventEnum.START), any());
        verify(stateMachine).transition(eq(3L), eq(MeetingEventEnum.END), any());
        verify(lock).unlock();
    }

    @Test
    void oneFailureShouldNotAbortTheRest() {
        when(meetingMapper.selectIdsToStart(anyInt())).thenReturn(List.of(1L, 2L, 3L));
        when(meetingMapper.selectIdsToFinish(anyInt())).thenReturn(List.of());
        // 第 2 条 CAS 没命中（状态已被人工改走）——同一批的第 3 条必须照常处理
        doThrow(new BusinessException(ErrorCode.MEETING_STATUS_NOT_ALLOWED))
                .when(stateMachine).transition(eq(2L), any(), any());

        task.advanceStatus();

        verify(stateMachine).transition(eq(1L), eq(MeetingEventEnum.START), any());
        verify(stateMachine).transition(eq(3L), eq(MeetingEventEnum.START), any());
    }

    @Test
    void shouldDoNothingWhenLockNotAcquired() throws InterruptedException {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        task.advanceStatus();

        // 多实例互斥：抢不到锁的这一轮**一条都不能动**，也不能去 unlock 别人的锁
        verify(stateMachine, never()).transition(anyLong(), any(), any());
        verify(lock, never()).unlock();
    }

    @Test
    void unlockShouldBeSkippedIfLockLost() throws InterruptedException {
        when(meetingMapper.selectIdsToStart(anyInt())).thenReturn(List.of());
        when(meetingMapper.selectIdsToFinish(anyInt())).thenReturn(List.of());
        // 看门狗没续上、锁已易主：此时 unlock 会抛 IllegalMonitorStateException，
        // 把 finally 自己变成异常源（正是 isHeldByCurrentThread 判断要防的）
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        task.advanceStatus();

        verify(lock, never()).unlock();
    }

    @Test
    void taskExceptionShouldNotEscape() throws InterruptedException {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(meetingMapper.selectIdsToStart(anyInt())).thenThrow(new RuntimeException("DB 抖动"));
        // 任务抛出去会被调度器吞掉并重排；这里要求它**自己兜住**（否则 finally 里的 unlock 也悬了）
        task.advanceStatus();
        verify(lock).unlock();
    }
}
