package com.smt.notification.mq;

import java.io.IOException;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.smt.common.mq.MeetingEventMessage;
import com.smt.common.mq.MeetingMqTopology;
import com.smt.notification.service.NotificationService;

/**
 * 会议事件消费者（T4.3；T4.5 接上 remind 队列）：幂等 → 重试 → 落库站内信 → 手动 ACK / 失败进死信。
 *
 * <h3>为什么手动 ACK（而不是让容器自动 ack）</h3>
 * 自动模式下容器在**监听方法返回时就 ack**——方法内部如果"catch 了异常只打日志"，
 * broker 会认为处理成功、消息被删掉，于是**失败静默变成成功**。
 * 手动 ack 把"什么时候算处理完"写进代码：只有业务处理正常返回才 ack。
 *
 * <h3>一条消息的完整旅程</h3>
 * <pre>
 *   已处理？ ──是─▶ ack（重复投递，丢弃）
 *      │否
 *      ▼
 *   业务处理（重试 3 次，指数退避）
 *      ├─失败─▶ basicNack(requeue=false) ─▶ 死信交换机 ─▶ meeting.dlq（人工排查；**不写标记**，重放可再处理）
 *      └─成功─▶ **写"已处理"标记** ─▶ ack（若 ack 失败：标记已在，重投会被幂等挡下，不重复发通知）
 * </pre>
 * <b>失败为什么是 nack 进死信、而不是 requeue 重投</b>：requeue 是**立即**重投，
 * 一条"永远处理不了"的消息会在消费者和 broker 之间无限打转（毒消息），把队列堵死；
 * 重试已经在代码里做过（3 次退避），还没成功说明不是抖动，该有人看一眼了。
 *
 * <h3>⚠️ 业务失败与 ack 失败**必须分开**（2026-10-16 评审 R2）</h3>
 * 初版把"业务处理 / 写幂等标记 / ack"塞进同一个 try，于是 ack 抛异常（连接刚断）会走进
 * "业务失败"的 catch：先把刚写好的**已处理**标记删掉，随后消息被重投 → 因为标记没了 → **重复发一遍通知**。
 * 幂等标记恰恰是为这个窗口准备的，不能在那条路径上把它删掉。
 *
 * <h3>并发度与"一个监听器挂 6 个队列"的代价</h3>
 * 6 个队列挂在**同一个** {@code @RabbitListener} 上、concurrency=1 ⇒ **一条消费线程**消费全部队列
 * （broker 里的 `consumers=6` 是 6 次 basicConsume，不是 6 条线程）。
 *
 * <p>单线程是有意的：**同队列内串行 ⇒ 同一 msgId 的两份投递一定是先后到达**，
 * 而"成功之后写标记"正好能挡住这种先后重复——不存在并发窗口（见 {@link ConsumeIdempotency}）。
 * 代价：一条毒消息的 3 次退避（约 1.5s）会占住这条线程、其他队列一起等；本项目通知量小，
 * 用"确定性去重"换这点吞吐是划算的。
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
        // ⚠️ 只记 msgId 与原因，**不打印整条消息**：消息体里是会议标题/审批意见等用户输入，
        // 项目在审计那边专门断言过"不记全文"（T4.11），这里同样不该把它倒进日志
        // （消息本身已经在 DLQ 里，要看去看那条）。
        if (message == null || message.msgId() == null || message.data() == null) {
            log.error("[MQ] 消息结构不合法（缺 msgId/data），转死信: msgId={}",
                    message == null ? "null" : message.msgId());
            nackQuietly(channel, deliveryTag);
            return;
        }

        String msgId = message.msgId();
        // 幂等检查（Redis 故障时这里会抛 → 消息不 ack、被重投，**不降级放行**：
        // 没有去重就可能重复发通知，而"等一下再处理"没有任何损失。与限流的"异常放行"方向相反，
        // 因为限流是保护、幂等是正确性前提）
        if (idempotency.isProcessed(msgId)) {
            log.info("[MQ] 重复投递，丢弃（该消息已成功处理过）: msgId={}", msgId);
            ackQuietly(channel, deliveryTag, msgId);
            return;
        }

        try {
            meetingEventRetryTemplate.execute(ctx -> {
                notificationService.handle(message.data());
                return null;
            });
            log.info("[MQ] 消费成功: msgId={}, type={}, meetingId={}",
                    msgId, message.data().type(), message.data().meetingId());
        } catch (Exception e) {
            // 业务失败：**不写标记** → 消息进死信，将来重放时能重新处理（这也省掉了"失败要撤销标记"那一步）
            log.error("[MQ] 消费失败（已重试 {} 次），转死信: msgId={}, type={}, meetingId={}",
                    MAX_ATTEMPTS, msgId, message.data().type(), message.data().meetingId(), e);
            nackQuietly(channel, deliveryTag);
            return;
        }

        // ★ 标记写在**成功之后**（评审 R1：写在处理前，崩溃时重投会被当成重复而丢弃 → 静默丢消息）。
        // 然后 ack 与业务失败分开：ack 失败只会让消息被重投，而标记已在 → 重投被正确判为重复 → 不会重复发通知
        idempotency.markProcessed(msgId);
        ackQuietly(channel, deliveryTag, msgId);
    }

    /** ack 失败只告警：消息会被重投，届时的幂等判定会把它挡下（不会重复发通知） */
    private void ackQuietly(Channel channel, long deliveryTag, String msgId) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.warn("[MQ] ack 失败（消息将被重投，幂等标记会挡下重复处理）: msgId={}, err={}", msgId, e.getMessage());
        }
    }

    /** nack 失败只告警：消息会留在 unacked，信道关闭后由 broker 重投（不会丢） */
    private void nackQuietly(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (Exception e) {
            log.warn("[MQ] nack 失败（消息将留在未确认状态，信道关闭后由 broker 重投）: {}", e.getMessage());
        }
    }
}
