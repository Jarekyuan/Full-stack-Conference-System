package com.smt.meeting.task;

import com.smt.meeting.config.MinutesProperties;
import com.smt.meeting.entity.MeetingMinutes;
import com.smt.meeting.mapper.MeetingMinutesMapper;
import com.smt.meeting.service.MinutesAiService;
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

/**
 * 纪要 AI 异步 worker（Task 9）：扫描待生成队列 + 恢复卡死任务。
 *
 * <h3>两级锁</h3>
 * <ul>
 *   <li>{@code task:minutes:ai}：整轮扫描的任务锁，多实例只有一个在跑，避免重复拉同一批；</li>
 *   <li>{@code lock:minutes:ai:{minutesId}}：条目锁，保证同一条纪要在同/跨轮次中只被一个
 *       worker 处理（拿到任务锁的实例可能因上一轮 LLM 调用很慢而重入下一轮）。</li>
 * </ul>
 * <p>两者都用 {@code tryLock(0)}：抢不到立即跳过，不排队（排队等来的那轮通常已无意义）。</p>
 *
 * <h3>卡死恢复</h3>
 * <p>worker 在 LLM 调用期间被杀死时，行会停在 20。每轮扫描把
 * {@code ai_started_at < now - stuck-timeout-minutes} 的行交给
 * {@link MinutesAiService#handleStuck}，按剩余次数回到 10 或 40。</p>
 *
 * <p>单条处理异常只记日志，不影响同批其他条目；下一轮扫描仍会命中未收敛的行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "minutes.ai.enabled", havingValue = "true", matchIfMissing = true)
public class MinutesAiTask {

    /** 任务锁 key（整轮扫描互斥） */
    public static final String TASK_LOCK = "task:minutes:ai";
    /** 条目锁前缀：lock:minutes:ai:{minutesId} */
    public static final String ITEM_LOCK_PREFIX = "lock:minutes:ai:";
    /** 每轮最多处理条数（剩余的下一轮继续，让单轮时长可控） */
    private static final int BATCH_SIZE = 5;
    private static final int DEFAULT_STUCK_TIMEOUT_MINUTES = 10;

    private final MeetingMinutesMapper minutesMapper;
    private final MinutesAiService aiService;
    private final MinutesProperties properties;
    private final RedissonClient redisson;

    /** 默认 5 秒一轮；间隔可用 `minutes.ai.scan-delay-ms` 覆盖。 */
    @Scheduled(scheduler = "minutesTaskScheduler",
            fixedDelayString = "${minutes.ai.scan-delay-ms:5000}")
    public void scan() {
        RLock taskLock = redisson.getLock(TASK_LOCK);
        boolean acquired = false;
        try {
            // waitTime=0：抢不到直接跳过本轮（与会议状态/提醒任务同一理由）
            acquired = taskLock.tryLock(0, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("[纪要 AI] 未抢到任务锁，本轮跳过（另一实例在跑）");
                return;
            }

            List<MeetingMinutes> pending = nonNull(minutesMapper.selectAiPending(BATCH_SIZE));
            for (MeetingMinutes minutes : pending) {
                withItemLock(minutes.getId(), () -> aiService.process(minutes.getId()));
            }

            int stuckTimeoutMinutes = stuckTimeoutMinutes();
            LocalDateTime before = LocalDateTime.now().minusMinutes(stuckTimeoutMinutes);
            List<MeetingMinutes> stuck = nonNull(minutesMapper.selectAiStuck(before, BATCH_SIZE));
            for (MeetingMinutes minutes : stuck) {
                withItemLock(minutes.getId(), () -> aiService.handleStuck(minutes.getId()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[纪要 AI] 获取任务锁被中断");
        } catch (Exception e) {
            // fixedDelay 下抛出会中断后续调度，任务异常必须吞在这里。
            log.error("[纪要 AI] 本轮扫描异常", e);
        } finally {
            release(taskLock, acquired);
        }
    }

    /**
     * 条目锁内执行；抢不到锁、被中断或业务异常都不向外扩散。
     * 异常逐条吞掉，保证同批其他纪要继续处理。
     */
    private void withItemLock(Long minutesId, Runnable action) {
        RLock lock = redisson.getLock(ITEM_LOCK_PREFIX + minutesId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("[纪要 AI] 未抢到条目锁，跳过 minutesId={}", minutesId);
                return;
            }
            action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[纪要 AI] 获取条目锁被中断 minutesId={}", minutesId);
        } catch (Exception e) {
            log.error("[纪要 AI] minutesId={} 处理失败", minutesId, e);
        } finally {
            release(lock, acquired);
        }
    }

    /** 解锁必须容错：锁可能因看门狗未续约而过期易主，不能让 finally 再抛异常。 */
    private void release(RLock lock, boolean acquired) {
        if (!acquired) {
            return;
        }
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("[纪要 AI] 释放锁失败 key={}", lock.getName(), e);
        }
    }

    private int stuckTimeoutMinutes() {
        Integer configured = properties.getAi().getStuckTimeoutMinutes();
        return configured == null || configured <= 0
                ? DEFAULT_STUCK_TIMEOUT_MINUTES : configured;
    }

    private static <T> List<T> nonNull(List<T> values) {
        return values == null ? List.of() : values;
    }
}
