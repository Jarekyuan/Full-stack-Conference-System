package com.smt.meeting.task;

import com.smt.meeting.entity.FileUploadSession;
import com.smt.meeting.enums.UploadSessionStatus;
import com.smt.meeting.mapper.FileUploadSessionMapper;
import com.smt.meeting.service.ObjectStorage;
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
 * 分片上传会话清理（Task 7）：把"已过期但仍进行中"的会话收敛为已过期(30)。
 *
 * <h3>两个幂等点</h3>
 * ① {@code storage.abortMultipart} 对不存在/已完成的会话是空操作，先删存储再改库不会误删已完成对象；
 * ② {@code markStatus} 是 CAS（仅 status=0 → 20/30），多实例或与用户 abort 并发时影响 0 行即视为他方已处理。
 *
 * <p>每条独立 try/catch：单个会话清理失败（网络/对象存储异常）不阻断同批其他会话，
 * 下一轮扫描还会再次捞到它（状态未变）。</p>
 *
 * <p>调度器使用 {@code minutesTaskScheduler}（2 线程）而非默认单线程：
 * 一轮最多 50 次远端 abort，不能堵住会议状态/提醒任务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "minutes.task.enabled", havingValue = "true", matchIfMissing = true)
public class FileUploadSessionTask {

    /** 任务锁 key（与会议任务分开：清理的是上传会话，互不干扰） */
    private static final String LOCK_KEY = "task:minutes:upload-session";
    /** 每轮最多清理条数（剩余的下轮继续，避免一次扫全表） */
    private static final int BATCH_SIZE = 50;

    private final FileUploadSessionMapper sessionMapper;
    private final ObjectStorage storage;
    private final RedissonClient redisson;

    /** 默认 10 分钟一轮；间隔可用 `minutes.task.session-delay-ms` 覆盖（验证脚本/测试会调小它） */
    @Scheduled(scheduler = "minutesTaskScheduler",
            fixedDelayString = "${minutes.task.session-delay-ms:600000}")
    public void cleanup() {
        RLock lock = redisson.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            // waitTime=0：抢不到直接跳过本轮（与 MeetingStatusTask 同一理由）
            acquired = lock.tryLock(0, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("[纪要上传清理] 未抢到锁，本轮跳过（另一实例在跑）");
                return;
            }
            cleanExpiredSessions();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[纪要上传清理] 本轮异常", e);
        } finally {
            // isHeldByCurrentThread 不能省：看门狗未续上时锁可能已易主，直接 unlock 会抛异常
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 扫描并清理一批过期会话（不含分布式锁，便于单测直接覆盖批处理语义）。
     *
     * @return 实际迁移为已过期(30) 的条数——{@code markStatus} 返回 0 说明已被他方处理，不计入
     */
    int cleanExpiredSessions() {
        List<FileUploadSession> expired = sessionMapper.selectExpired(
                LocalDateTime.now(), BATCH_SIZE);
        int cleaned = 0;
        for (FileUploadSession session : expired) {
            try {
                storage.abortMultipart(session.getObjectKey(), session.getUploadId());
                if (sessionMapper.markStatus(
                        session.getUploadId(), UploadSessionStatus.EXPIRED) == 1) {
                    cleaned++;
                }
            } catch (Exception e) {
                // 单条失败不影响整批：状态仍为进行中，下一轮会再次命中
                log.warn("[纪要上传清理] 会话 {} 清理失败", session.getUploadId(), e);
            }
        }
        if (!expired.isEmpty()) {
            log.info("[纪要上传清理] 本轮扫描 {} 个过期会话，成功清理 {} 个",
                    expired.size(), cleaned);
        }
        return cleaned;
    }
}
