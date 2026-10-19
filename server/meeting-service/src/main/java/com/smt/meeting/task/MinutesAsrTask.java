package com.smt.meeting.task;

import com.smt.meeting.config.AsrProperties;
import com.smt.meeting.entity.MeetingMinutes;
import com.smt.meeting.mapper.MeetingMinutesMapper;
import com.smt.meeting.service.MinutesAsrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 腾讯 ASR 异步 worker：提交待识别任务 + 轮询识别中任务 + 恢复超时任务。
 *
 * <p>与 {@link MinutesAiTask} 同一套锁策略：任务锁只包住“选择 id”，
 * 每条任务在条目锁内处理；慢的供应商调用不会阻塞其他实例扫描。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "minutes.asr.enabled", havingValue = "true", matchIfMissing = true)
public class MinutesAsrTask {

    public static final String TASK_LOCK = "task:minutes:asr";
    public static final String ITEM_LOCK_PREFIX = "lock:minutes:asr:job:";
    private static final int DEFAULT_BATCH_SIZE = 5;
    private static final int DEFAULT_STUCK_TIMEOUT_MINUTES = 120;

    private final MeetingMinutesMapper minutesMapper;
    private final MinutesAsrService asrService;
    private final AsrProperties properties;
    private final RedissonClient redisson;

    @Scheduled(scheduler = "minutesTaskScheduler",
            fixedDelayString = "${minutes.asr.poll-interval-ms:5000}")
    public void scan() {
        List<Long> pending = selectIds(
                () -> minutesMapper.selectAsrPending(batchSize()), "待提交");
        List<Long> running = selectIds(
                () -> minutesMapper.selectAsrRunning(batchSize()), "识别中");
        LocalDateTime before = LocalDateTime.now()
                .minusMinutes(stuckTimeoutMinutes());
        List<Long> stuck = selectIds(
                () -> minutesMapper.selectAsrStuck(before, batchSize()), "超时");

        for (Long minutesId : pending) {
            withItemLock(minutesId, () -> asrService.process(minutesId));
        }
        for (Long minutesId : running) {
            withItemLock(minutesId, () -> asrService.process(minutesId));
        }
        for (Long minutesId : stuck) {
            withItemLock(minutesId, () -> asrService.handleStuck(minutesId));
        }
    }

    private List<Long> selectIds(Supplier<List<MeetingMinutes>> selection, String scene) {
        RLock taskLock = redisson.getLock(TASK_LOCK);
        boolean acquired = false;
        try {
            acquired = taskLock.tryLock(0, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("[纪要 ASR] 未抢到任务锁，跳过{}选择（另一实例在跑）", scene);
                return List.of();
            }
            return nonNull(selection.get()).stream()
                    .map(MeetingMinutes::getId)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[纪要 ASR] 获取任务锁被中断 scene={}", scene);
            return List.of();
        } catch (Exception e) {
            log.error("[纪要 ASR] 选择{}任务异常", scene, e);
            return List.of();
        } finally {
            release(taskLock, acquired);
        }
    }

    private void withItemLock(Long minutesId, Runnable action) {
        RLock lock = redisson.getLock(ITEM_LOCK_PREFIX + minutesId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("[纪要 ASR] 未抢到条目锁，跳过 minutesId={}", minutesId);
                return;
            }
            action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[纪要 ASR] 获取条目锁被中断 minutesId={}", minutesId);
        } catch (Exception e) {
            log.error("[纪要 ASR] minutesId={} 处理失败", minutesId, e);
        } finally {
            release(lock, acquired);
        }
    }

    private void release(RLock lock, boolean acquired) {
        if (!acquired) {
            return;
        }
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("[纪要 ASR] 释放锁失败 key={}", lock.getName(), e);
        }
    }

    private int batchSize() {
        Integer configured = properties.getBatchSize();
        return configured == null || configured <= 0 ? DEFAULT_BATCH_SIZE : configured;
    }

    private int stuckTimeoutMinutes() {
        Integer configured = properties.getStuckTimeoutMinutes();
        return configured == null || configured <= 0
                ? DEFAULT_STUCK_TIMEOUT_MINUTES : configured;
    }

    private static <T> List<T> nonNull(List<T> values) {
        return values == null ? List.of() : values;
    }
}
