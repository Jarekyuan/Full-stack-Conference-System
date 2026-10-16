package com.smt.notification.mq;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 消费幂等（T4.3）：`mq:idem:{msgId}` —— 记的是"这条消息**已经成功处理过**"。
 *
 * <h3>⚠️ 标记必须在**成功之后**写（2026-10-16 评审 R1，我第一版改错了）</h3>
 * 正确的时序是"先处理、成功才标记"：
 * <pre>
 *   已处理？ ──是─▶ ack（重复投递，丢弃）
 *      │否
 *      ▼
 *   处理（重试 3 次）─失败─▶ nack 进死信（**不写标记** → 重放死信时能重新处理）
 *      │成功
 *      ▼
 *   写"已处理"标记 ─▶ ack（ack 若失败：消息重投时标记已在 → 被正确判为重复，不会重复发通知）
 * </pre>
 *
 * <p>**在"处理之前"写标记是错的**——无论叫它 P（处理中）还是 S（已处理），后果都一样：
 * 进程在处理中崩溃 → broker 立刻重投 → 新进程看到标记还在 → 判为重复 → ack 丢弃
 * → **这条通知永远不会生成**，而日志写着"重复投递，已丢弃"，看起来完全正常。
 * （初版用 P/S 双状态，我第一版"修复"只是把 P 去掉、标记仍写在前面——后果一模一样，
 * 是评审 agent 的追问让我发现改错了方向。）
 *
 * <p>代价：**极少数并发重复**（同一 msgId 的两份同时被处理，且都成功）会重复发一条通知——
 * 这在单消费者/单实例下几乎不可能（一条消息只投给一个消费者），而它换掉的是"崩溃后必丢"。
 * 取舍明确：**宁可重复一条通知，不可静默丢失**（重复只是打扰一次，丢失是用户永远不知道）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumeIdempotency {

    /** 与 docs/02 6.4 的 `mq:idem:{msgId}` 对齐 */
    private static final String KEY_PREFIX = "mq:idem:";
    /** 24 小时：挡重放的窗口（docs/02 6.4）。超过 24h 的重投会重复通知一次，换取 key 不无限堆积 */
    private static final Duration DONE_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    /** 这条消息是否已经成功处理过（重复投递要跳过） */
    public boolean isProcessed(String msgId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + msgId));
        } catch (Exception e) {
            // Redis 故障时**不让它静默通过**：抛出去 → 消息不 ack、被重投（幂等是正确性前提，
            // 不像限流那样"异常放行"——没有去重就可能重复发通知，而"等一下再处理"没有任何损失）
            throw e;
        }
    }

    /** 处理成功后才标记（见类注释：标记写在处理之前 = 崩溃后丢消息） */
    public void markProcessed(String msgId) {
        redis.opsForValue().set(KEY_PREFIX + msgId, "S", DONE_TTL);
    }
}
