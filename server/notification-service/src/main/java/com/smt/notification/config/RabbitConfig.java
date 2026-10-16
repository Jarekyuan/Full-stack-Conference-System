package com.smt.notification.config;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import lombok.extern.slf4j.Slf4j;

import com.smt.common.mq.MeetingEventType;
import com.smt.common.mq.MeetingMqTopology;

/**
 * RabbitMQ 拓扑与消费端基础设施（T4.3）。
 *
 * <p>拓扑定义与生产端**引用同一份** {@link MeetingMqTopology}：队列参数（尤其 `x-dead-letter-exchange`）
 * 只要两边不一致，第二个声明方就会 `PRECONDITION_FAILED`、连消费者都起不来。
 * 两边都声明是**有意**的——谁先启动谁建，任何一个服务单独起都能把拓扑补齐，不依赖"生产端必须先启动"。
 */
@Slf4j
@Configuration
public class RabbitConfig {

    /**
     * 消息转换器：JSON，且**反序列化按监听方法签名推断类型**（{@code TypePrecedence.INFERRED}）。
     *
     * <p>Spring AMQP 默认用消息头 `__TypeId__` 里的类名去加载类——那是**生产端塞进来的、不可信的输入**：
     * 谁能往交换机发消息，谁就能指定消费端去实例化任意类（Jackson 反序列化 gadget 的经典入口）。
     * 改成"类型取自监听方法参数"，消息头里的类名直接被忽略：
     * 既关掉了这个面，也让消费端不必依赖生产端的包名（契约靠共享模块保证，不靠反射字符串）。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        converter.setTypePrecedence(DefaultJackson2JavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
    }

    /**
     * 消费失败的重试策略：3 次、指数退避（0.5s → 1s）。
     *
     * <p>为什么在**代码里**重试（RetryTemplate）而不是开 `spring.rabbitmq.listener.simple.retry`：
     * ① 容器级重试只在"监听方法把异常抛出去"时生效，而我们用**手动 ACK**——
     * 失败后到底是 ack、nack 还是重投，必须由代码明确决定，不能让容器在背后替我们猜；
     * ② 两层重试会相乘（容器 3 次 × 代码 3 次 = 9 次），排障时根本算不清一条消息被处理了几次。
     * 重试耗尽后由消费者显式 `basicNack(requeue=false)`，消息进死信队列——去哪，代码说了算。
     */
    @Bean
    public RetryTemplate meetingEventRetryTemplate() {
        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(2000);

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setBackOffPolicy(backOff);
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(3));
        return retryTemplate;
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
     * 启动即声明拓扑。
     *
     * <p>生产端是懒连接（见 meeting-service 的同名 Bean），消费端本来就要在建连时注册消费者，
     * 这里显式初始化一次是为了让"服务起来了但队列还没建"这种状态在**启动日志**里就能看见，
     * 而不是等到第一条消息投不进来才发现。失败同样不阻断启动（MQ 是旁路依赖）。
     */
    @Bean
    public ApplicationRunner rabbitTopologyInitializer(AmqpAdmin amqpAdmin) {
        return args -> {
            try {
                amqpAdmin.initialize();
                log.info("[MQ] 拓扑已声明：{} + {}（死信）", MeetingMqTopology.EXCHANGE,
                        MeetingMqTopology.DEAD_LETTER_QUEUE);
            } catch (Exception e) {
                log.warn("[MQ] 启动时声明拓扑失败（不影响启动，监听器建连时会再试）: {}", e.getMessage());
            }
        };
    }

    // ---- 与生产端同源的事件队列 ----

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
        // 提醒队列：T4.5 才会往里投消息，这里先声明好（消费端接没接、队列在不在，管理台一眼可见）
        return MeetingMqTopology.eventQueue(MeetingEventType.REMIND);
    }

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
