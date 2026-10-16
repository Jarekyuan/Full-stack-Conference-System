package com.smt.meeting.task;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.smt.common.mq.MeetingDomainEvent;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.mapper.MeetingMapper;

/**
 * 会议提醒扫描（T4.5）：把"开始前 15 分钟"的会议挑出来，发 {@code meeting.remind} 事件。
 *
 * <h3>为什么是"扫描"而不是"TTL+DLX 延迟消息"（计划文档原方案）</h3>
 * 原方案的推理是"提醒提前量固定 15 分钟 → 天然单一延迟，规避队头阻塞"，**这个前提不成立**：
 * 固定的是**触发提前量**，不是**消息 TTL**（TTL = 开始时间 − 15min − 下单时刻，随下单时间千差万别）。
 * 本项目预约可提前 30 天 → 同队列里 TTL 从几分钟到 30 天 → 队头那条 30 天的消息会把后面
 * 所有提醒堵满 30 天（RabbitMQ 只在消息到队头时判过期）。分桶方案在桶内仍有同样的问题、
 * 还要把调度逻辑编码进消息头；延迟插件本机镜像里没有（实测）。完整对比见 docs/02 5.4。
 *
 * <h3>扫描方式的三个好处（选它的理由，不只是"没别的路"）</h3>
 * ① **没有快照可过期**——原计划要处理"改期后旧消息还在路上"，扫描读的永远是库里的当前值，
 *    这个问题连同它的兜底校验一起消失；
 * ② **幂等不靠消息 id**——`SETNX remind:sent:{meetingId}:{开始时刻}` 里带上开始时刻，
 *    改期后 key 自然换新（重新提醒），延期/改标题则不会重复提醒；
 * ③ 与已有的 {@link MeetingStatusTask} **同构**（Redisson 锁 + 分批 + 单条失败不影响整批），
 *    零新增基础设施、零新增运维面。
 *
 * <h3>代价（如实写在这）</h3>
 * 精度受扫描间隔限制（默认 1 分钟）；多实例要靠分布式锁互斥。
 * 另：MQ 不可用时这条提醒会丢（Publisher 吞异常只告警，与 T4.1 记录的"接受漏发"一致）。
 * 要更稳应把"已提醒"落库 + 由对账任务重发，属于可靠性增强，不在本轮范围。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "meeting.task.enabled", havingValue = "true", matchIfMissing = true)
public class MeetingRemindTask {

    /** 任务锁 key（与状态流转分开：提醒是只读扫描 + 发消息，没必要和写状态的任务互斥） */
    public static final String LOCK_KEY = "task:meeting:remind";
    /** 提前量（分钟）：会议开始前多久提醒，与 docs/04 5.3 的"开始前 15 分钟"一致 */
    public static final int LEAD_MINUTES = 15;
    /** 每批条数 */
    private static final int BATCH_SIZE = 200;
    /** 已提醒标记的 key 前缀：remind:sent:{meetingId}:{yyyyMMddHHmm} */
    private static final String SENT_KEY_PREFIX = "remind:sent:";
    /**
     * 标记的存活时间：只需盖住提醒窗口（15 分钟）+ 扫描间隔的抖动。
     * 用 1 小时——窗口结束后 key 已经没用，留着只是占内存；太短又会在窗口内重复提醒。
     */
    private static final long SENT_TTL_MINUTES = 60;

    private static final DateTimeFormatter KEY_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final MeetingMapper meetingMapper;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher eventPublisher;
    private final RedissonClient redissonClient;

    /** 默认每分钟一轮；间隔可用 `meeting.task.remind.delay-ms` 覆盖（验证脚本会调小它） */
    @Scheduled(fixedDelayString = "${meeting.task.remind.delay-ms:60000}")
    public void remindUpcoming() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            // waitTime=0：抢不到就直接跳过本轮（同 MeetingStatusTask 的理由——排队等来的那轮会立刻又被抢走）
            acquired = lock.tryLock(0, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("[提醒任务] 未抢到锁，本轮跳过（另一实例在跑）");
                return;
            }
            int sent = scanOnce();
            if (sent > 0) {
                log.info("[提醒任务] 已发出 {} 条会议提醒", sent);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[提醒任务] 获取任务锁被中断");
        } catch (Exception e) {
            // 任务异常不能让调度线程死掉（fixedDelay 下抛出会中断后续调度）
            log.error("[提醒任务] 本轮执行异常", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 扫一轮；返回实际发出的提醒数 */
    private int scanOnce() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.plusMinutes(LEAD_MINUTES);
        List<Meeting> due = meetingMapper.selectToRemind(now, deadline, BATCH_SIZE);

        int sent = 0;
        for (Meeting m : due) {
            try {
                if (markSentIfAbsent(m)) {
                    // 无事务上下文：Publisher 的 AFTER_COMMIT 监听器靠 fallbackExecution=true 立即执行
                    // （这正是当初把它打开的意义——否则"没事务就不发"，表现是"功能都正常、就是没提醒"）
                    eventPublisher.publishEvent(MeetingDomainEvent.remind(
                            m.getId(), m.getTitle(), m.getRoomId(), m.getOrganiserId(),
                            m.getStartTime(), m.getEndTime(), m.getStatus()));
                    sent++;
                }
            } catch (Exception e) {
                // 单条失败不影响整批
                log.error("[提醒任务] 会议 {} 提醒发送失败", m.getId(), e);
            }
        }
        return sent;
    }

    /**
     * 抢占"这场会这个开始时刻"的提醒权；抢到返回 true。
     *
     * <p>为什么要带上**开始时刻**：这是本方案"改期一致性"的全部实现——
     * 会议改到新时间后 key 变了，新时间自然会再提醒一次；而改标题、延长结束时间都不影响 key。
     * （原计划要用"消费端比对消息里的 start_time"来兜这个，现在不需要了。）
     */
    private boolean markSentIfAbsent(Meeting m) {
        String key = SENT_KEY_PREFIX + m.getId() + ":" + KEY_TIME.format(m.getStartTime());
        Boolean first = redis.opsForValue().setIfAbsent(key, "1", Duration.ofMinutes(SENT_TTL_MINUTES));
        return Boolean.TRUE.equals(first);
    }
}
