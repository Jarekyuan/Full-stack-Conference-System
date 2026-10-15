package com.smt.meeting.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

import com.smt.meeting.event.MeetingEventType;

/**
 * RabbitMQ 拓扑（T4.1）。
 *
 * <p>拓扑：一个 topic exchange {@code meeting.events} + 每个事件类型一个队列，队列名 == 路由键。
 * 用 topic 而非 direct/fanout：路由键带层级（`meeting.created`），将来加"预约域"下的新事件
 * （如 `meeting.recurring.*`）或按域通配订阅（`meeting.#`）都不用改拓扑。
 *
 * <p>为什么每个事件一个队列、而不是一个队列吃全部：通知的**处理策略按场景不同**
 * （提醒要校验时间是否改过、取消要通知参会人），分开后每个消费者只关心自己那类，
 * 某类消费卡住也不会堵住其他类型。代价是队列数量随事件类型增长——当前 6 个，可控。
 *
 * <p>⚠️ 这里声明的是**生产者视角**的拓扑。notification-service（T4.2 起）也会声明同样的队列：
 * 两个服务都声明同名同参数的对象是幂等的（RabbitMQ 对同参数重复声明返回成功），
 * 谁先启动谁创建，不存在"生产者先起、队列还没建、消息被丢"的时序问题——
 * 这正是"声明式拓扑"相对"控制台手工建队列"的价值。
 */
@Slf4j
@Configuration
public class RabbitConfig {

    /** 会议事件交换机（topic） */
    public static final String EXCHANGE = "meeting.events";

    /**
     * 消息转换器：JSON。
     * 默认的 SimpleMessageConverter 走 Java 序列化——控制台里看到的是一串二进制乱码，
     * 排障基本靠猜；JSON 既能在管理台直接读，也避免"消费端必须与生产端同一个类版本"这种耦合。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public TopicExchange meetingEventExchange() {
        return new TopicExchange(EXCHANGE, true, false); // durable=true, autoDelete=false
    }

    /**
     * 启动即声明拓扑（踩过的坑）。
     *
     * <p>Spring AMQP 生产端是**懒连接**：不主动连 RabbitMQ 的话，队列要等第一次发消息才被创建。
     * 于是"服务刚起来、还没连过 MQ"的那段时间里发出的消息，会因为交换机/队列尚不存在而被**静默丢弃**
     * ——topic exchange 路由不到任何队列就是直接丢，不报错、不重试（实测：只声明不初始化时，
     * 管理台里一个队列都没有）。
     *
     * <p>**失败不阻断启动**：RabbitMQ 是旁路依赖，它没起来不该让会议服务起不来（同 docs/02 的
     * "非核心依赖不拖垮核心链路"）。声明失败只告警，等第一次真正发送时 RabbitAdmin 会在建连后补声明。
     *
     * <p>⚠️ 注入类型必须是 {@link AmqpAdmin} 接口：Boot 自动配置里的 @Bean 方法**声明返回 AmqpAdmin**
     * （实际实例是 RabbitAdmin），而按类型注入匹配的是**声明类型**——写成 RabbitAdmin 会
     * "找不到 bean"、启动直接失败（实测踩到）。
     */
    @Bean
    public ApplicationRunner rabbitTopologyInitializer(AmqpAdmin amqpAdmin) {
        return args -> {
            try {
                amqpAdmin.initialize();
            } catch (Exception e) {
                log.warn("[MQ] 启动时声明拓扑失败（不影响启动，首次发送时会再试）: {}", e.getMessage());
            }
        };
    }

    /** 一个事件类型一个队列（队列名 == 路由键，排障时一眼对得上；逐个声明是因为 @Bean 不支持批量） */
    @Bean
    public Queue meetingCreatedQueue() {
        return QueueBuilder.durable(MeetingEventType.CREATED.routingKey()).build();
    }

    @Bean
    public Queue meetingApprovedQueue() {
        return QueueBuilder.durable(MeetingEventType.APPROVED.routingKey()).build();
    }

    @Bean
    public Queue meetingRejectedQueue() {
        return QueueBuilder.durable(MeetingEventType.REJECTED.routingKey()).build();
    }

    @Bean
    public Queue meetingCancelledQueue() {
        return QueueBuilder.durable(MeetingEventType.CANCELLED.routingKey()).build();
    }

    @Bean
    public Queue meetingUpdatedQueue() {
        return QueueBuilder.durable(MeetingEventType.UPDATED.routingKey()).build();
    }

    @Bean
    public Queue meetingRemindQueue() {
        // 提醒队列（T4.5 由延迟队列经 DLX 投递过来），此处先声明以便消费端提前接好
        return QueueBuilder.durable(MeetingEventType.REMIND.routingKey()).build();
    }

    // ---- 绑定：队列名即路由键（topic exchange 下精确路由键是精确匹配） ----

    @Bean
    public Binding bindCreated(Queue meetingCreatedQueue, TopicExchange meetingEventExchange) {
        return BindingBuilder.bind(meetingCreatedQueue).to(meetingEventExchange)
                .with(MeetingEventType.CREATED.routingKey());
    }

    @Bean
    public Binding bindApproved(Queue meetingApprovedQueue, TopicExchange meetingEventExchange) {
        return BindingBuilder.bind(meetingApprovedQueue).to(meetingEventExchange)
                .with(MeetingEventType.APPROVED.routingKey());
    }

    @Bean
    public Binding bindRejected(Queue meetingRejectedQueue, TopicExchange meetingEventExchange) {
        return BindingBuilder.bind(meetingRejectedQueue).to(meetingEventExchange)
                .with(MeetingEventType.REJECTED.routingKey());
    }

    @Bean
    public Binding bindCancelled(Queue meetingCancelledQueue, TopicExchange meetingEventExchange) {
        return BindingBuilder.bind(meetingCancelledQueue).to(meetingEventExchange)
                .with(MeetingEventType.CANCELLED.routingKey());
    }

    @Bean
    public Binding bindUpdated(Queue meetingUpdatedQueue, TopicExchange meetingEventExchange) {
        return BindingBuilder.bind(meetingUpdatedQueue).to(meetingEventExchange)
                .with(MeetingEventType.UPDATED.routingKey());
    }
}
