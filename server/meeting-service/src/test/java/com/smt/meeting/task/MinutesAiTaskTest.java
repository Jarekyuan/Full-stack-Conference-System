package com.smt.meeting.task;

import com.smt.meeting.config.MinutesProperties;
import com.smt.meeting.entity.MeetingMinutes;
import com.smt.meeting.mapper.MeetingMinutesMapper;
import com.smt.meeting.service.MinutesAiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 9 worker：选择阶段短任务锁、条目锁互斥、单条异常隔离、解锁容错、卡死恢复。
 *
 * <p>尤其钉住 I1：任务锁必须在调用 {@code aiService.process} 之前释放，
 * 一个慢 LLM 调用不能阻塞另一实例的扫描/卡死恢复。</p>
 */
class MinutesAiTaskTest {

    private MeetingMinutesMapper minutesMapper;
    private MinutesAiService aiService;
    private MinutesProperties properties;
    private RedissonClient redisson;
    private RLock taskLock;
    private Map<Long, RLock> itemLocks;
    private MinutesAiTask task;

    @BeforeEach
    void setUp() throws InterruptedException {
        minutesMapper = mock(MeetingMinutesMapper.class);
        aiService = mock(MinutesAiService.class);
        properties = new MinutesProperties();
        redisson = mock(RedissonClient.class);
        taskLock = mock(RLock.class);
        itemLocks = new ConcurrentHashMap<>();

        when(redisson.getLock(MinutesAiTask.TASK_LOCK)).thenReturn(taskLock);
        when(taskLock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(taskLock.isHeldByCurrentThread()).thenReturn(true);
        when(taskLock.getName()).thenReturn(MinutesAiTask.TASK_LOCK);
        when(redisson.getLock(startsWith(MinutesAiTask.ITEM_LOCK_PREFIX)))
                .thenAnswer(invocation -> itemLock(invocation.getArgument(0)));

        task = new MinutesAiTask(minutesMapper, aiService, properties, redisson);
    }

    // ---------- 夹具 ----------

    private MeetingMinutes minutes(long id) {
        MeetingMinutes minutes = new MeetingMinutes();
        minutes.setId(id);
        minutes.setAiStatus(10);
        return minutes;
    }

    private void stubPending(Long... ids) {
        List<MeetingMinutes> rows = java.util.Arrays.stream(ids)
                .map(this::minutes)
                .toList();
        when(minutesMapper.selectAiPending(MinutesAiTask.BATCH_SIZE)).thenReturn(rows);
    }

    private RLock itemLock(String name) {
        long id = Long.parseLong(name.substring(MinutesAiTask.ITEM_LOCK_PREFIX.length()));
        return itemLocks.computeIfAbsent(id, key -> {
            RLock lock = mock(RLock.class);
            try {
                when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            when(lock.isHeldByCurrentThread()).thenReturn(true);
            when(lock.getName()).thenReturn(name);
            return lock;
        });
    }

    private RLock itemLock(long id) {
        return itemLock(MinutesAiTask.ITEM_LOCK_PREFIX + id);
    }

    // ---------- 选择阶段 ----------

    @Test
    void taskLockNotAcquiredShouldNotSelectAnything() throws InterruptedException {
        when(taskLock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        task.scan();

        verify(minutesMapper, never()).selectAiPending(anyInt());
        verify(minutesMapper, never()).selectAiStuck(any(LocalDateTime.class), anyInt());
        verify(taskLock, never()).unlock();
    }

    @Test
    void interruptedTaskLockShouldRestoreFlag() throws InterruptedException {
        when(taskLock.tryLock(0, TimeUnit.SECONDS))
                .thenThrow(new InterruptedException("interrupted"));
        try {
            task.scan();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(minutesMapper, never()).selectAiPending(anyInt());
    }

    @Test
    void selectionExceptionShouldNotEscape() {
        when(minutesMapper.selectAiPending(MinutesAiTask.BATCH_SIZE))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(task::scan).doesNotThrowAnyException();

        verify(taskLock, times(2)).unlock();
    }

    @Test
    void taskLockShouldBeReleasedBeforeProcessing() {
        stubPending(1L);

        task.scan();

        InOrder order = inOrder(taskLock, aiService);
        order.verify(taskLock).unlock();
        order.verify(aiService).process(1L);
    }

    @Test
    void slowProcessShouldNotBlockSecondScanSelection() throws Exception {
        stubPending(1L);
        when(minutesMapper.selectAiStuck(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of());

        // 模拟真实的"锁当前是否被持有"：tryLock 抢到才置 true，unlock 置 false
        AtomicBoolean taskLockHeld = new AtomicBoolean(false);
        when(taskLock.tryLock(0, TimeUnit.SECONDS))
                .thenAnswer(invocation -> taskLockHeld.compareAndSet(false, true));
        doAnswer(invocation -> {
            taskLockHeld.set(false);
            return null;
        }).when(taskLock).unlock();

        CountDownLatch processStarted = new CountDownLatch(1);
        CountDownLatch releaseProcess = new CountDownLatch(1);
        AtomicInteger processCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (processCalls.incrementAndGet() == 1) {
                processStarted.countDown();
                if (!releaseProcess.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test latch timeout");
                }
            }
            return null;
        }).when(aiService).process(anyLong());

        MinutesAiTask secondTask = new MinutesAiTask(
                minutesMapper, aiService, properties, redisson);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> firstScan = executor.submit(task::scan);
        try {
            assertThat(processStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // 第一个实例仍卡在 LLM 调用里，第二个实例必须能抢到任务锁并完成选择
            secondTask.scan();
            assertThat(processCalls.get()).isEqualTo(2);
        } finally {
            releaseProcess.countDown();
            firstScan.get(5, TimeUnit.SECONDS);
            executor.shutdownNow();
        }

        verify(minutesMapper, times(2)).selectAiPending(MinutesAiTask.BATCH_SIZE);
    }

    // ---------- 条目锁 ----------

    @Test
    void itemLockNotAcquiredShouldSkipProcessing() throws InterruptedException {
        stubPending(1L);
        RLock itemLock = itemLock(1L);
        when(itemLock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);
        when(itemLock.isHeldByCurrentThread()).thenReturn(false);

        task.scan();

        verify(aiService, never()).process(anyLong());
        verify(itemLock, never()).unlock();
    }

    @Test
    void itemExceptionShouldNotStopBatchAndStillUnlock() {
        stubPending(1L, 2L);
        doThrow(new RuntimeException("boom")).when(aiService).process(1L);

        task.scan();

        verify(aiService).process(1L);
        verify(aiService).process(2L);
        verify(itemLock(1L)).unlock();
        verify(itemLock(2L)).unlock();
    }

    @Test
    void interruptedItemLockShouldRestoreFlag() throws InterruptedException {
        stubPending(1L);
        RLock itemLock = itemLock(1L);
        when(itemLock.tryLock(0, TimeUnit.SECONDS))
                .thenThrow(new InterruptedException("interrupted"));
        try {
            task.scan();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(aiService, never()).process(anyLong());
    }

    @Test
    void unlockFailureShouldNotEscape() {
        stubPending(1L);
        RLock itemLock = itemLock(1L);
        doThrow(new RuntimeException("unlock failed")).when(taskLock).unlock();
        doThrow(new RuntimeException("unlock failed")).when(itemLock).unlock();

        assertThatCode(task::scan).doesNotThrowAnyException();

        verify(aiService).process(1L);
    }

    // ---------- 卡死恢复 ----------

    @Test
    void stuckIdsShouldBeRecoveredAfterTaskLockReleased() {
        when(minutesMapper.selectAiPending(MinutesAiTask.BATCH_SIZE)).thenReturn(List.of());
        when(minutesMapper.selectAiStuck(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(minutes(5L)));

        task.scan();

        verify(aiService).handleStuck(5L);
        InOrder order = inOrder(taskLock, aiService);
        order.verify(taskLock).unlock();
        order.verify(aiService).handleStuck(5L);
    }

    @Test
    void stuckItemExceptionShouldNotEscape() {
        when(minutesMapper.selectAiPending(MinutesAiTask.BATCH_SIZE)).thenReturn(List.of());
        when(minutesMapper.selectAiStuck(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(minutes(5L)));
        doThrow(new RuntimeException("boom")).when(aiService).handleStuck(5L);

        assertThatCode(task::scan).doesNotThrowAnyException();

        verify(itemLock(5L)).unlock();
    }
}
