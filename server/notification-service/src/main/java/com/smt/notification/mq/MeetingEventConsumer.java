package com.smt.notification.mq;

import java.io.IOException;

import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.smt.common.mq.MeetingEventMessage;
import com.smt.common.mq.MeetingMqTopology;
import com.smt.notification.mq.ConsumeIdempotency.AcquireResult;
import com.smt.notification.service.NotificationService;

/**
 * 会议事件消费者（T4.3；T4.5 接上 remind 队列）：幂等 → 重试 → 落库站内信 → 手动 ACK / 失败进死信。
 *
 * <h3>为什么手动 ACK（而不是让容器自动 ack）</h3>
 * 自动模式下容器在**监听方法返回时就 ack**——方法内部如果"catch 了异常只打日志"，
 * broker 会认为处理成功、消息被删掉，于是**失败静默变成成功**。
 * 手动 ack 把"什么时候算处理完"写进代码：只有 {@code NotificationService.handle} 正常返回才 ack。
 *
 * <h3>一条消息的完整旅程</h3>
 * <pre>
 *   幂等取用 ──已处理过/处理中──▶ ack（丢弃重复投递）
 *      │ACQUIRED
 *      ▼
 *   业务处理（重试 3 次，指数退避）
 *      ├─成功─▶ 标记完成 ─▶ ack
 *      └─失败─▶ 释放幂等标记 ─▶ basicNack(requeue=false) ─▶ 死信交换机 ─▶ meeting.dlq（人工排查）
 * </pre>
 * <b>失败为什么是 nack 进死信、而不是 requeue 重投</b>：requeue 是**立即**重投，
 * 一条"永远处理不了"的消息会在消费者和 broker 之间无限打转（毒消息），把队列堵死；
 * 重试已经在代码里做过（3 次退避），还没成功说明不是抖动，该有人看一眼了。
 *
 * <h3>并发度</h3>
 * 每个队列一个消费者（yml 里 concurrency=1）。同队列内串行 ⇒ 同一条消息不会被两个线程同时处理，
 * 幂等判断也就不存在"两个线程同时看到 key 不存在"的竞态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingEventConsumer {

    /** 与 RabbitConfig 里 RetryTemplate 的次数一致（这里只用于日志文案） */
    private static final int MAX_ATTEMPTS = 3;

    private final NotificationService notificationService;
    private final ConsumeIdempotency idempotency;
    private final RetryTemplate meetingEventRetryTemplate;

    @RabbitListener(queues = {
            MeetingMqTopology.QUEUE_CREATED,
            MeetingMqTopology.QUEUE_APPROVED,
            MeetingMqTopology.QUEUE_REJECTED,
            MeetingMqTopology.QUEUE_CANCELLED,
            MeetingMqTopology.QUEUE_UPDATED,
            MeetingMqTopology.QUEUE_REMIND})
    public void onMeetingEvent(MeetingEventMessage message, Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        // 结构不合法的消息：重试多少次都不会变好，直接进死信等人工看。
        // （JSON 都解析不出来的那种更早一步就被容器拦下了，走 yml 的 default-requeue-rejected=false 进死信）
        if (message == null || message.msgId() == null || message.data() == null) {
            log.error("[MQ] 消息结构不合法（缺 msgId/data），转死信: {}", message);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        String msgId = message.msgId();
        AcquireResult acquired = idempotency.acquire(msgId);
        if (acquired != AcquireResult.ACQUIRED) {
            // 两种情况都直接 ack：已处理过的重复投递（DONE），和同一 msgId 的另一份正在处理（IN_FLIGHT）。
            // IN_FLIGHT 那份要么最终成功（它自己 ack），要么失败进死信——消息不会凭空消失。
            log.info("[MQ] 重复投递，丢弃: msgId={}, 状态={}", msgId, acquired);
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            meetingEventRetryTemplate.execute(ctx -> {
                notificationService.handle(message.data());
                return null;
            });
            idempotency.markDone(msgId);
            channel.basicAck(deliveryTag, false);
            log.info("[MQ] 消费成功: msgId={}, type={}, meetingId={}",
                    msgId, message.data().type(), message.data().meetingId());
        } catch (Exception e) {
            // 先释放幂等标记再进死信：将来从死信队列重放这条消息时，不该被自己的旧标记挡住
            idempotency.release(msgId);
            log.error("[MQ] 消费失败（已重试 {} 次），转死信: msgId={}, type={}, meetingId={}",
                    MAX_ATTEMPTS, msgId, message.data().type(), message.data().meetingId(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
