package com.smt.notification.mq;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 消费幂等（T4.3）：`SETNX mq:idem:{msgId}`，成功才处理。
 *
 * <p>为什么消费端**必须**自己去重：MQ 的投递语义是 at-least-once——消费者 ack 前掉线、
 * 网络抖动、重试，都会让同一条消息再来一次。broker 不保证"只投一次"，
 * 去重只能由知道业务语义的那一端做。
 *
 * <h3>key 的值带状态（P 处理中 / S 已完成），不是一个裸标记</h3>
 * 和 HTTP 幂等切面（{@code IdempotentAspect}）同一个道理，但**这个场景更尖锐**：
 * 如果只用"key 存在 = 处理过"，那么"处理失败 → 重试"时，重试会撞上自己刚写的标记、
 * 被判成重复消息直接 ack 掉——**消息就这么静默丢了**，而且日志上看到的是"重复消息，已丢弃"，
 * 像是正常的去重。所以失败路径必须**显式释放**标记（{@link #release}）。
 *
 * <h3>两个 TTL 为什么不一样</h3>
 * P（处理中）10 分钟：只用来盖住"一次处理 + 重试"的时间窗，越短越好——
 * 进程在处理中崩溃时，这条消息重投回来还能重新处理（P 已过期），不至于是"永久性跳过"。
 * S（已完成）24 小时：挡重放（docs/02 6.4 的键设计）。超过 24h 的重投会重复通知一次，
 * 这个代价换取"key 不会无限堆积"，对通知来说是可接受的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumeIdempotency {

    /** 与 docs/02 6.4 的 `mq:idem:{msgId}` 对齐 */
    private static final String KEY_PREFIX = "mq:idem:";
    private static final String STATE_PROCESSING = "P";
    private static final String STATE_DONE = "S";
    private static final Duration PROCESSING_TTL = Duration.ofMinutes(10);
    private static final Duration DONE_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    /** 取用结果：拿到了（该我处理） / 已处理过（丢弃） / 正在处理中（丢弃重复投递） */
    public enum AcquireResult {
        ACQUIRED, DONE, IN_FLIGHT
    }

    public AcquireResult acquire(String msgId) {
        String key = KEY_PREFIX + msgId;
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, STATE_PROCESSING, PROCESSING_TTL))) {
            return AcquireResult.ACQUIRED;
        }
        return STATE_DONE.equals(redis.opsForValue().get(key))
                ? AcquireResult.DONE
                : AcquireResult.IN_FLIGHT;
    }

    /** 处理成功：把标记改成"已完成"并延长到 24h */
    public void markDone(String msgId) {
        redis.opsForValue().set(KEY_PREFIX + msgId, STATE_DONE, DONE_TTL);
    }

    /** 处理失败：撤销标记，否则重试/重投会被自己的标记挡掉（见类注释） */
    public void release(String msgId) {
        redis.delete(KEY_PREFIX + msgId);
    }
}
