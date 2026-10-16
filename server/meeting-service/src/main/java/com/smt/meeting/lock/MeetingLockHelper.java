package com.smt.meeting.lock;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 预约分布式锁（T2.6；docs/01 R-8 + docs/02 5.2）
 *
 * 锁设计：
 * ① key = {@code lock:meeting:{roomId}:{yyyyMMdd}} —— 粒度"会议室 + 日期"：
 *    比"整会议室"细（不同日互不阻塞）、比"精确时段"简单可靠（区间端点整理成本高且易错）；
 * ② 跨天会议**逐日加锁**，且日期用 TreeSet 升序 —— 多把锁按固定顺序获取，避免死锁（两人分别订跨天会时）；
 * ③ tryLock(waitTime=3s, leaseTime=-1)：拿不到锁最多等 3 秒（期间其他请求可能已完成并释放），
 *    leaseTime=-1 启用 Redisson **看门狗**（自动续期，业务未结束不会因锁超时被抢）；
 * ④ finally 中 isHeldByCurrentThread 判断后释放 —— 只释放自己持有的锁。
 *
 * 与事务的顺序（关键）：调用方必须保证 **锁的范围 ≥ 事务的范围**（锁外开、锁内提），
 * 否则"锁释放 → 事务提交"之间存在空窗：后一个请求拿到锁后读不到未提交数据，重复检测会失效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingLockHelper {

    private static final String KEY_PREFIX = "lock:meeting:";
    private static final long WAIT_SECONDS = 3;

    private final RedissonClient redisson;

    /**
     * 在锁保护下执行 action（锁覆盖 start~end 涉及的每个自然日）。
     *
     * 【为什么把 acquire 也放进 try】acquire 中途失败时必须释放"已拿到的那几把"——
     * leaseTime=-1 走看门狗会自动续期，漏放一把就等于把该 (roomId, 日期) **永久**锁死
     * （后续所有预约都在 3 秒后 10007，直到服务重启）。
     * 原先只在 `catch (InterruptedException | BusinessException)` 里释放，
     * 而跨天取锁途中 Redis 抖动抛的是 `RedisTimeoutException`（RuntimeException，两个 catch 都接不住）
     * → 异常穿透、已持有的锁无人释放（评审 C3）。
     *
     * @throws BusinessException 10007 抢锁失败（等待 3 秒仍未获得）
     */
    public <T> T executeWithLock(Long roomId, LocalDateTime start, LocalDateTime end, Supplier<T> action) {
        List<RLock> locks = new ArrayList<>();
        try {
            acquireInto(locks, roomId, start, end);
            return action.get();
        } finally {
            release(locks); // 无论 acquire 中途失败还是 action 抛异常，都兜底释放已持有的锁
        }
    }

    /**
     * 在锁保护下执行 action，**锁覆盖给定的这批自然日**（T5.1 周期会议用）。
     *
     * <p>为什么需要这个重载：周期会议一次创建 N 场，日期是**不连续**的（每周一场）。
     * 用"覆盖首末区间"的版本会把中间每一天都锁上——那些天根本没有本系列的会议，
     * 却会让别人的单场预约白等 3 秒（严重时并发高就直接 10007）。
     * 只锁"真的要写的那几天"，锁的语义才与"改变这几天的占用"对齐。
     *
     * <p>日期必须是同一把锁体系里的 key（`lock:meeting:{roomId}:{date}`）——
     * 与单场创建共用，系列创建与并发单场预约才会互斥（红线的要求）。
     */
    public <T> T executeWithLockOnDates(Long roomId, SortedSet<LocalDate> dates, Supplier<T> action) {
        List<RLock> locks = new ArrayList<>();
        try {
            acquireInto(locks, roomId, dates);
            return action.get();
        } finally {
            release(locks);
        }
    }

    /** 逐日取锁；取到几把就往 locks 里加几把（中途失败由调用方的 finally 统一释放） */
    private void acquireInto(List<RLock> locks, Long roomId, LocalDateTime start, LocalDateTime end) {
        // 覆盖 [start 当天 .. end 当天]（端点日期也纳入：宁可多锁一天，避免半开区间的边界漏洞）
        SortedSet<LocalDate> dates = new TreeSet<>();
        for (LocalDate d = start.toLocalDate(); !d.isAfter(end.toLocalDate()); d = d.plusDays(1)) {
            dates.add(d);
        }
        acquireInto(locks, roomId, dates);
    }

    /** 逐日取锁（日期已定）：TreeSet 再排一次，保证**无论调用方怎么传**都按升序取，避免死锁 */
    private void acquireInto(List<RLock> locks, Long roomId, SortedSet<LocalDate> inputDates) {
        SortedSet<LocalDate> dates = new TreeSet<>(inputDates);

        for (LocalDate date : dates) {
            RLock lock = redisson.getLock(KEY_PREFIX + roomId + ":" + date);
            try {
                if (!lock.tryLock(WAIT_SECONDS, -1, TimeUnit.SECONDS)) {
                    throw new BusinessException(ErrorCode.SYSTEM_BUSY);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.SYSTEM_BUSY);
            }
            locks.add(lock);
        }
        log.debug("预约锁已获取: room={}, dates={}", roomId, dates);
    }

    /**
     * 释放已持有的锁。逐把 try/catch：
     * 一把 unlock 失败（如连接已断）不能让后面几把漏放，也不能覆盖掉原始业务异常
     * （否则 40001 会被变成 500）。
     */
    private void release(List<RLock> locks) {
        for (RLock lock : locks) {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                log.warn("释放预约锁失败（已跳过，等待租约/看门狗兜底）: {}", lock.getName(), e);
            }
        }
    }
}
