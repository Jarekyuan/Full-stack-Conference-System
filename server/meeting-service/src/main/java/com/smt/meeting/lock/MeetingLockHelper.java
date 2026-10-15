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
     * @throws BusinessException 10007 抢锁失败（等待 3 秒仍未获得）
     */
    public <T> T executeWithLock(Long roomId, LocalDateTime start, LocalDateTime end, Supplier<T> action) {
        List<RLock> locks = acquire(roomId, start, end);
        try {
            return action.get();
        } finally {
            release(locks);
        }
    }

    private List<RLock> acquire(Long roomId, LocalDateTime start, LocalDateTime end) {
        // 覆盖 [start 当天 .. end 当天]（端点日期也纳入：宁可多锁一天，避免半开区间的边界漏洞）
        SortedSet<LocalDate> dates = new TreeSet<>();
        for (LocalDate d = start.toLocalDate(); !d.isAfter(end.toLocalDate()); d = d.plusDays(1)) {
            dates.add(d);
        }

        List<RLock> acquired = new ArrayList<>(dates.size());
        try {
            for (LocalDate date : dates) {
                RLock lock = redisson.getLock(KEY_PREFIX + roomId + ":" + date);
                if (!lock.tryLock(WAIT_SECONDS, -1, TimeUnit.SECONDS)) {
                    throw new BusinessException(ErrorCode.SYSTEM_BUSY);
                }
                acquired.add(lock);
            }
            log.debug("预约锁已获取: room={}, dates={}", roomId, dates);
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            release(acquired); // 中断也要释放已持有的锁
            throw new BusinessException(ErrorCode.SYSTEM_BUSY);
        } catch (BusinessException e) {
            release(acquired); // 中途某把锁失败：释放已获取的，避免残留占用
            throw e;
        }
    }

    private void release(List<RLock> locks) {
        for (RLock lock : locks) {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
