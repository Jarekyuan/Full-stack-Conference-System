package com.smt.common.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * 会议事件 MQ 拓扑（T4.3）：交换机 / 队列 / 绑定的**唯一定义处**，生产端与消费端共用。
 *
 * <h3>为什么拓扑要放共享库，而不是各服务抄一份</h3>
 * RabbitMQ 的队列参数是**建立时定死**的：同名队列第二次声明时，参数不一致会直接
 * {@code PRECONDITION_FAILED}（406），连接被关掉、消费者起不来。
 * 所以"生产端声明一份、消费端抄一份"这种写法，只要有人改了一边（哪怕只是加了死信参数），
 * 另一个服务就再也起不来了；而这种错误在编译期**完全没有提示**，只有运行时才炸。
 * 放一份共享定义，两端由构造保证一致。
 *
 * <h3>拓扑结构</h3>
 * <pre>
 *   meeting.events (topic)                     ← 业务交换机
 *     ├─ meeting.created   ─┐
 *     ├─ meeting.approved   │  每个事件一个队列（队列名 == 路由键）
 *     ├─ meeting.rejected   │  都挂 x-dead-letter-exchange
 *     ├─ meeting.cancelled  │
 *     ├─ meeting.updated    │
 *     └─ meeting.remind    ─┘
 *              │ 重试耗尽 / 消息格式非法
 *              ▼
 *   meeting.events.dlx (topic) → meeting.dlq   ← 死信交换机 + 死信队列
 * </pre>
 *
 * <h3>死信为什么用"一个 DLQ"而不是每个事件一个</h3>
 * 死信是**排障通道**，不是业务通道：队列数量越少越好查（T4.3 的验证脚本就是直接读这一条队列）。
 * 原始路由键不会丢——死信消息在 DLQ 里仍带着自己的 routing key 与 {@code x-death} 头，
 * 一眼能看出是哪个事件、被哪个队列死信了几次。真按事件类型拆 DLQ，只会让人在 6 个队列之间来回找。
 */
public final class MeetingMqTopology {

    /** 业务交换机（topic）：路由键带层级，将来加 {@code meeting.recurring.*} 或按域通配订阅都不用改拓扑 */
    public static final String EXCHANGE = "meeting.events";
    /** 死信交换机 */
    public static final String DEAD_LETTER_EXCHANGE = "meeting.events.dlx";
    /** 死信队列（所有事件的死信都汇到这里） */
    public static final String DEAD_LETTER_QUEUE = "meeting.dlq";
    /** 死信队列的绑定键：覆盖本域全部路由键 */
    private static final String DEAD_LETTER_BINDING = "meeting.#";

    // ---- 队列名（== 路由键）----
    // 为什么不直接写在 MeetingEventType 里：@RabbitListener(queues = ...) 的值必须是**编译期常量**，
    // 而枚举的方法调用（`MeetingEventType.CREATED.routingKey()`）不是。
    // 所以字符串常量收在这里一份，枚举引用它、注解也引用它——两边永远同源，不会出现"队列名与路由键对不上"。

    public static final String QUEUE_CREATED = "meeting.created";
    public static final String QUEUE_APPROVED = "meeting.approved";
    public static final String QUEUE_REJECTED = "meeting.rejected";
    public static final String QUEUE_CANCELLED = "meeting.cancelled";
    public static final String QUEUE_UPDATED = "meeting.updated";
    public static final String QUEUE_REMIND = "meeting.remind";

    private MeetingMqTopology() {
    }

    public static TopicExchange eventExchange() {
        return new TopicExchange(EXCHANGE, true, false); // durable=true, autoDelete=false
    }

    public static TopicExchange deadLetterExchange() {
        return new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    /**
     * 事件队列：队列名 == 路由键（排障时管理台里一眼对得上）。
     *
     * <p>带 {@code x-dead-letter-exchange}：消费端重试耗尽（显式 nack(requeue=false)）
     * 或消息本身反序列化失败（容器按"不可重试"处理）时，消息转投死信交换机。
     * 不设 {@code x-dead-letter-routing-key}：默认沿用原路由键，正好被 DLX 上的
     * {@code meeting.#} 接住，且保留"是哪个事件死的"这条信息。
     */
    public static Queue eventQueue(MeetingEventType type) {
        return QueueBuilder.durable(type.routingKey())
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .build();
    }

    public static Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    public static Binding bindEventQueue(Queue queue, TopicExchange exchange, MeetingEventType type) {
        return BindingBuilder.bind(queue).to(exchange).with(type.routingKey());
    }

    public static Binding bindDeadLetterQueue(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DEAD_LETTER_BINDING);
    }
}
