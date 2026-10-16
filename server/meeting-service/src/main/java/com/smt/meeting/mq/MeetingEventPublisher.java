package com.smt.meeting.mq;

import java.util.UUID;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.smt.common.mq.MeetingDomainEvent;
import com.smt.common.mq.MeetingEventMessage;
import com.smt.common.mq.MeetingMqTopology;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 领域事件 → MQ（T4.1，docs/02 5.2 第 7 步）。
 *
 * <h3>为什么是 AFTER_COMMIT</h3>
 * "发消息"是不可回滚的副作用。若在事务内直接发，事务随后回滚就会出现
 * **下游收到一场并不存在的会议**（通知发了、日历缓存清了，而库里什么都没有）。
 * 挂在 {@link TransactionPhase#AFTER_COMMIT} 上，保证"消息只描述已经落库的事实"。
 *
 * <h3>为什么 fallbackExecution = true</h3>
 * 默认（false）下，**没有事务时监听器根本不执行**——将来某个写路径忘了开事务，
 * 表现是"功能都正常、就是没有通知"，属于最难查的静默失败。
 * 打开它，无事务时立即执行（此时业务数据已写入自动提交的事务，语义同样成立）。
 *
 * <h3>为什么这里必须 try/catch</h3>
 * AFTER_COMMIT 的监听器抛异常会**冒泡回 commit 的调用方**（Spring 的
 * `TransactionSynchronizationUtils.invokeAfterCommit` 不做吞异常处理）：
 * 数据已经提交了，接口却因为"发消息失败"报 500——用户看到失败、实际成功，
 * 会重复提交。所以这里只告警不抛：**通知是旁路，不能反过来影响主流程**。
 * （代价是这条消息丢了；MQ 的可靠投递/补偿属于另一个话题，本项目按 docs/02 的取舍"接受漏发"。）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMeetingEvent(MeetingDomainEvent event) {
        MeetingEventMessage message = MeetingEventMessage.of(UUID.randomUUID().toString(), event);
        String routingKey = message.routingKey();
        try {
            rabbitTemplate.convertAndSend(MeetingMqTopology.EXCHANGE, routingKey, message);
            log.info("[MQ] 已发送事件 {} meetingId={} msgId={}", routingKey, event.meetingId(), message.msgId());
        } catch (Exception e) {
            // 不抛（见类注释）；ERROR 级别因为这是"通知链路断了"，需要人知道
            log.error("[MQ] 事件发送失败（业务已提交，本条通知丢失）: routingKey={}, meetingId={}, msgId={}",
                    routingKey, event.meetingId(), message.msgId(), e);
        }
    }
}
