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
import java.util.function.Supplier;

/**
 * 纪要 AI 异步 worker（Task 9）：扫描待生成队列 + 恢复卡死任务。
 *
 * <h3>锁的范围（关键）</h3>
 * <p>{@code task:minutes:ai} 只包住"选择待处理 id"这一小段，<b>在调用 LLM 前就释放</b>。
 * 早期实现把任务锁持有到整批处理结束：一个实例顺序调用 5 次 LLM 时，其他实例既不能扫描，
 * 也无法及时做卡死恢复。现在选择与处理分离：</p>
 * <ol>
 *   <li>短任务锁 → 选出 pending id 列表 → 释放；</li>
 *   <li>逐条在 {@code lock:minutes:ai:{id}} 条目锁内处理，条目锁 + 状态 CAS（含生成令牌）
 *       共同防止重复处理；</li>
 *   <li>短任务锁 → 选出卡死 id 列表 → 释放 → 逐条条目锁内恢复。</li>
 * </ol>
 *
 * <h3>重试节奏</h3>
 * <p>失败后 {@code ai_status} 回到 10，下一轮（默认 5 秒）立即重扫。LLM 超时/网络抖动多为瞬时，
 * 刻意不引入退避列；尝试次数由 {@code minutes.ai.max-attempts} 约束，不会无限付费重试。</p>
 *
 * <p>单条处理异常只记日志，不影响同批其他条目与卡死扫描。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "minutes.ai.enabled", havingValue = "true", matchIfMissing = true)
public class MinutesAiTask {

    /** 任务锁 key（只保护"选择"阶段，不覆盖处理阶段） */
    public static final String TASK_LOCK = "task:minutes:ai";
    /** 条目锁前缀：lock:minutes:ai:{minutesId} */
    public static final String ITEM_LOCK_PREFIX = "lock:minutes:ai:";
    /** 每轮最多选择条数（剩余的下轮继续，让单轮时长可控） */
    static final int BATCH_SIZE = 5;
    private static final int DEFAULT_STUCK_TIMEOUT_MINUTES = 10;

    private final MeetingMinutesMapper minutesMapper;
    private final MinutesAiService aiService;
    private final MinutesProperties properties;
    private final RedissonClient redisson;

    /** 默认 5 秒一轮；间隔可用 `minutes.ai.scan-delay-ms` 覆盖。 */
    @Scheduled(scheduler = "minutesTaskScheduler",
            fixedDelayString = "${minutes.ai.scan-delay-ms:5000}")
    public void scan() {
        // 1) 两次短锁只做选择；选完立刻释放，后面的 LLM 调用/恢复都不再占用任务锁。
        List<Long> pendingIds = selectIds(
                () -> minutesMapper.selectAiPending(BATCH_SIZE), "待生成");
        LocalDateTime before = LocalDateTime.now().minusMinutes(stuckTimeoutMinutes());
        List<Long> stuckIds = selectIds(
                () -> minutesMapper.selectAiStuck(before, BATCH_SIZE), "卡死");

        // 2) 处理阶段只靠条目锁 + 状态 CAS；慢 LLM 不会阻塞其他实例的扫描。
        for (Long minutesId : pendingIds) {
            withItemLock(minutesId, () -> aiService.process(minutesId));
        }
        for (Long minutesId : stuckIds) {
            withItemLock(minutesId, () -> aiService.handleStuck(minutesId));
        }
    }

    /**
     * 在短任务锁内执行一次 id 选择。
     *
     * <p>抢不到锁（另一实例正在选择）或选择异常时返回空列表：本轮跳过即可，
     * 未处理的行仍在库里，下一轮/其他实例会继续。</p>
     */
    private List<Long> selectIds(Supplier<List<MeetingMinutes>> selection, String scene) {
        RLock taskLock = redisson.getLock(TASK_LOCK);
        boolean acquired = false;
        try {
            // waitTime=0：抢不到直接跳过本轮（与会议状态/提醒任务同一理由）
            acquired = taskLock.tryLock(0, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("[纪要 AI] 未抢到任务锁，跳过{}选择（另一实例在跑）", scene);
                return List.of();
            }
            return nonNull(selection.get()).stream()
                    .map(MeetingMinutes::getId)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[纪要 AI] 获取任务锁被中断 scene={}", scene);
            return List.of();
        } catch (Exception e) {
            log.error("[纪要 AI] 选择{}纪要异常", scene, e);
            return List.of();
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
