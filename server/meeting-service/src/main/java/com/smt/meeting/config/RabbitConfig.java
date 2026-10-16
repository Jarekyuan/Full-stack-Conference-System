package com.smt.meeting.config;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

import com.smt.common.mq.MeetingEventType;
import com.smt.common.mq.MeetingMqTopology;

/**
 * RabbitMQ 拓扑（T4.1，生产端视角）。
 *
 * <p>交换机/队列/绑定的**定义**不在这里，在 {@link MeetingMqTopology}（meeting-common）——
 * 因为消费端（notification-service）要声明**一模一样**的一份，而队列参数不一致会
 * {@code PRECONDITION_FAILED}（详见该类注释）。这里只负责把共享定义注册成 Bean。
 *
 * <p>为什么每个事件一个队列、而不是一个队列吃全部：通知的**处理策略按场景不同**
 * （提醒要校验时间是否改过、取消要通知参会人）；分开后每类事件可以独立观察/独立重放
 * （管理台一眼看出哪类积压），死信也能按 routingKey 认出是哪种事件。
 * ⚠️ 但**"分开队列"不等于"互不阻塞"**：消费端 6 个队列挂在同一个 @RabbitListener 上，
 * 默认一条线程消费全部队列（见 MeetingEventConsumer 的说明），所以一条毒消息的退避会占住那条线程。
 * 代价是队列数量随事件类型增长——当前 6 个，可控。
 */
@Slf4j
@Configuration
public class RabbitConfig {

    /**
     * 消息转换器：JSON。
     * 默认的 SimpleMessageConverter 走 Java 序列化——控制台里看到的是一串二进制乱码，
     * 排障基本靠猜；JSON 既能在管理台直接读，也避免"消费端必须与生产端同一个类版本"这种耦合。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 把"发了但没人收"变成**看得见**（2026-10-16 评审 O5）。
     *
     * <p>yml 里开了 `publisher-confirm-type: correlated` 与 `publisher-returns: true`，
     * 但那只是"允许 broker 告诉你"——**不注册回调就什么也听不到**：
     * ① broker 拒收（nack）→ ConfirmCallback；② 路由不到任何队列（topic 没有匹配的绑定）→ ReturnsCallback。
     * 后者正是最容易被忽略的一种"消息静默消失"（发送端一切正常、管理台什么都看不到）。
     * 注册之后至少日志里有 ERROR，排障不用猜。
     *
     * <p>注意：这里**不重发**。可靠投递（本地消息表/重试）是另一个量级的话题，
     * 本项目按 docs/02 的取舍"接受漏发 + 告警"，只是把"漏了"从静默变成可见。
     */
    @Bean
    public RabbitTemplateCustomizer rabbitTemplateCustomizer() {
        return template -> {
            template.setMandatory(true);
            template.setConfirmCallback((correlationData, ack, cause) -> {
                if (!ack) {
                    log.error("[MQ] broker 未确认消息（nack）: cause={}", cause);
                }
            });
            template.setReturnsCallback(returned -> log.error(
                    "[MQ] 消息路由不到任何队列，已被丢弃: exchange={}, routingKey={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        };
    }

    @Bean
    public TopicExchange meetingEventExchange() {
        return MeetingMqTopology.eventExchange();
    }

    @Bean
    public TopicExchange meetingDeadLetterExchange() {
        return MeetingMqTopology.deadLetterExchange();
    }

    @Bean
    public Queue meetingDeadLetterQueue() {
        return MeetingMqTopology.deadLetterQueue();
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

    // ---- 事件队列（队列名 == 路由键，逐个声明是因为 @Bean 不支持批量） ----

    @Bean
    public Queue meetingCreatedQueue() {
        return MeetingMqTopology.eventQueue(MeetingEventType.CREATED);
    }

    @Bean
    public Queue meetingApprovedQueue() {
        return MeetingMqTopology.eventQueue(MeetingEventType.APPROVED);
    }

    @Bean
    public Queue meetingRejectedQueue() {
        return MeetingMqTopology.eventQueue(MeetingEventType.REJECTED);
    }

    @Bean
    public Queue meetingCancelledQueue() {
        return MeetingMqTopology.eventQueue(MeetingEventType.CANCELLED);
    }

    @Bean
    public Queue meetingUpdatedQueue() {
        return MeetingMqTopology.eventQueue(MeetingEventType.UPDATED);
    }

    @Bean
    public Queue meetingRemindQueue() {
        // 提醒队列（T4.5：由**定时扫描**在开始前 15 分钟窗口内投递，见 MeetingRemindTask / docs/02 5.4）
        return MeetingMqTopology.eventQueue(MeetingEventType.REMIND);
    }

    // ---- 绑定：队列名即路由键（topic exchange 下精确路由键是精确匹配） ----

    @Bean
    public Binding bindCreated(Queue meetingCreatedQueue, TopicExchange meetingEventExchange) {
        return MeetingMqTopology.bindEventQueue(meetingCreatedQueue, meetingEventExchange, MeetingEventType.CREATED);
    }

    @Bean
    public Binding bindApproved(Queue meetingApprovedQueue, TopicExchange meetingEventExchange) {
        return MeetingMqTopology.bindEventQueue(meetingApprovedQueue, meetingEventExchange, MeetingEventType.APPROVED);
    }

    @Bean
    public Binding bindRejected(Queue meetingRejectedQueue, TopicExchange meetingEventExchange) {
        return MeetingMqTopology.bindEventQueue(meetingRejectedQueue, meetingEventExchange, MeetingEventType.REJECTED);
    }

    @Bean
    public Binding bindCancelled(Queue meetingCancelledQueue, TopicExchange meetingEventExchange) {
        return MeetingMqTopology.bindEventQueue(meetingCancelledQueue, meetingEventExchange, MeetingEventType.CANCELLED);
    }

    @Bean
    public Binding bindUpdated(Queue meetingUpdatedQueue, TopicExchange meetingEventExchange) {
        return MeetingMqTopology.bindEventQueue(meetingUpdatedQueue, meetingEventExchange, MeetingEventType.UPDATED);
    }

    @Bean
    public Binding bindRemind(Queue meetingRemindQueue, TopicExchange meetingEventExchange) {
        return MeetingMqTopology.bindEventQueue(meetingRemindQueue, meetingEventExchange, MeetingEventType.REMIND);
    }

    @Bean
    public Binding bindDeadLetterQueue(Queue meetingDeadLetterQueue, TopicExchange meetingDeadLetterExchange) {
        return MeetingMqTopology.bindDeadLetterQueue(meetingDeadLetterQueue, meetingDeadLetterExchange);
    }
}
