package com.smt.meeting.task;

import com.smt.meeting.entity.FileUploadSession;
import com.smt.meeting.enums.UploadSessionStatus;
import com.smt.meeting.mapper.FileUploadSessionMapper;
import com.smt.meeting.service.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 7：过期上传会话清理任务（对照 {@link MeetingStatusTaskTest}）。
 *
 * <p>钉住四条性质：① 抢不到锁什么都不做；② 单条失败不影响整批；
 * ③ 先 abort 存储再标记数据库；④ markStatus 返回 0（他方已处理）不计入清理数。</p>
 */
class FileUploadSessionTaskTest {

    private static final String OBJECT_KEY = "minutes/42/202610/x.m4a";

    private FileUploadSessionMapper sessionMapper;
    private ObjectStorage storage;
    private RLock lock;
    private FileUploadSessionTask task;

    @BeforeEach
    void setUp() throws InterruptedException {
        sessionMapper = mock(FileUploadSessionMapper.class);
        storage = mock(ObjectStorage.class);
        RedissonClient redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        task = new FileUploadSessionTask(sessionMapper, storage, redisson);
    }

    private FileUploadSession session(String uploadId) {
        FileUploadSession session = new FileUploadSession();
        session.setUploadId(uploadId);
        session.setObjectKey(OBJECT_KEY);
        return session;
    }

    @Test
    void shouldDoNothingWhenLockNotAcquired() throws InterruptedException {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        task.cleanup();

        // 多实例互斥：抢不到锁的这一轮一条都不能碰，也不能 unlock 别人的锁
        verify(sessionMapper, never()).selectExpired(any(), anyInt());
        verify(lock, never()).unlock();
    }

    @Test
    void shouldCountOnlySessionsActuallyMarkedExpired() {
        when(sessionMapper.selectExpired(any(), anyInt()))
                .thenReturn(List.of(session("u1"), session("u2")));
        when(sessionMapper.markStatus("u1", UploadSessionStatus.EXPIRED)).thenReturn(1);
        // u2 已被用户 abort 或另一实例处理：CAS 影响 0 行，不计入
        when(sessionMapper.markStatus("u2", UploadSessionStatus.EXPIRED)).thenReturn(0);

        int cleaned = task.cleanExpiredSessions();

        assertThat(cleaned).isEqualTo(1);
        verify(storage).abortMultipart(OBJECT_KEY, "u1");
        verify(storage).abortMultipart(OBJECT_KEY, "u2");
    }

    @Test
    void oneFailureShouldNotAbortTheRest() {
        when(sessionMapper.selectExpired(any(), anyInt()))
                .thenReturn(List.of(session("u1"), session("u2")));
        doThrow(new RuntimeException("COS down"))
                .when(storage).abortMultipart(OBJECT_KEY, "u1");
        when(sessionMapper.markStatus("u2", UploadSessionStatus.EXPIRED)).thenReturn(1);

        int cleaned = task.cleanExpiredSessions();

        assertThat(cleaned).isEqualTo(1);
        // u1 的 abort 失败 → 不能改状态（下一轮还会重试）；u2 必须照常处理
        verify(sessionMapper, never()).markStatus(eq("u1"), anyInt());
        verify(sessionMapper).markStatus("u2", UploadSessionStatus.EXPIRED);
    }

    @Test
    void abortShouldHappenBeforeMarkingExpired() {
        when(sessionMapper.selectExpired(any(), anyInt()))
                .thenReturn(List.of(session("u1")));
        when(sessionMapper.markStatus("u1", UploadSessionStatus.EXPIRED)).thenReturn(1);

        task.cleanExpiredSessions();

        InOrder order = inOrder(storage, sessionMapper);
        order.verify(storage).abortMultipart(OBJECT_KEY, "u1");
        order.verify(sessionMapper).markStatus("u1", UploadSessionStatus.EXPIRED);
    }

    @Test
    void unlockShouldBeSkippedIfLockLost() {
        when(sessionMapper.selectExpired(any(), anyInt())).thenReturn(List.of());
        // 看门狗没续上、锁已易主：直接 unlock 会抛 IllegalMonitorStateException
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        task.cleanup();

        verify(lock, never()).unlock();
    }

    @Test
    void taskExceptionShouldNotEscape() {
        when(sessionMapper.selectExpired(any(), anyInt()))
                .thenThrow(new RuntimeException("DB 抖动"));

        task.cleanup();

        verify(lock).unlock();
    }

    @Test
    void interruptShouldRestoreFlagAndNotScan() throws InterruptedException {
        when(lock.tryLock(0, TimeUnit.SECONDS))
                .thenThrow(new InterruptedException("interrupted"));
        try {
            task.cleanup();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // 清掉中断标志，避免影响同一线程上的后续用例
            Thread.interrupted();
        }
        verify(sessionMapper, never()).selectExpired(any(), anyInt());
        verify(lock, never()).unlock();
    }
}
