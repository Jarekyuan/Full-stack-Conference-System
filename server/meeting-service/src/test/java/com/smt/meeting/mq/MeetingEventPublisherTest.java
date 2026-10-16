package com.smt.meeting.mq;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import com.smt.common.mq.MeetingMqTopology;
import com.smt.common.mq.MeetingDomainEvent;
import com.smt.common.mq.MeetingEventMessage;

/**
 * T4.1 验收：**消息只在事务提交后发出**（docs/02 5.2 第 7 步）。
 *
 * <p>为什么用 mock 顶掉 RabbitTemplate 而不是真往 MQ 里发：
 * 这个测试要验的是"**什么时候发**"（AFTER_COMMIT 的时序语义），不是"MQ 能不能发出去"——
 * 后者由 `scripts/verify-m4-mq.sh` 对着真 broker 验。用 mock 之后这两个用例**完全确定性**
 * （不依赖 broker 状态、不产生垃圾消息），而且能把"回滚时不发"这条**逆向**场景真正测出来——
 * 走 HTTP 接口是构造不出"事件已发布、事务随后回滚"的：那种失败点发生在业务代码的最后一步之后。
 */
@SpringBootTest(properties = "spring.cloud.nacos.discovery.enabled=false")
class MeetingEventPublisherTest {

    @MockBean
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private MeetingDomainEvent sampleEvent() {
        return MeetingDomainEvent.created(1L, "MQ 时序验证", 6L, 4L,
                LocalDateTime.of(2026, 12, 1, 10, 0), LocalDateTime.of(2026, 12, 1, 11, 0), 20);
    }

    @Test
    void committedTransactionShouldPublishMessage() {
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(sampleEvent()));

        // 第三个参数要写具体类型：RabbitTemplate 有 (String,Object,CorrelationData) 与
        // (String,String,Object) 两个三参重载，用裸 any() 会编译期歧义
        verify(rabbitTemplate, times(1))
                .convertAndSend(eq(MeetingMqTopology.EXCHANGE), eq("meeting.created"), any(MeetingEventMessage.class));
    }

    @Test
    void rolledBackTransactionShouldNotPublishMessage() {
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                eventPublisher.publishEvent(sampleEvent());
                // 事件已发布、事务随后回滚——这正是 AFTER_COMMIT 要挡住的场景：
                // 若在事务内直接发 MQ，下游就会收到一场"从未存在过"的会议
                throw new IllegalStateException("模拟业务失败，事务回滚");
            });
        } catch (IllegalStateException expected) {
            // 预期内
        }

        verifyNoInteractions(rabbitTemplate);
    }
}
