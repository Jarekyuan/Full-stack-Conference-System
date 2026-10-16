package com.smt.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.smt.common.mq.MeetingDomainEvent;
import com.smt.notification.entity.Notification;
import com.smt.notification.mapper.NotificationMapper;
import com.smt.notification.mapper.RecipientMapper;

/**
 * T4.10 补齐：通知生成（T4.3~T4.5）的场景与收件人规则。
 *
 * <p>脚本 `verify-m4-notify-consume.sh` 验的是端到端链路；这里钉住的是**文案与收件人规则**本身——
 * 它们是 docs/04 5.3 的接口契约（前端按 type 映射图标、按 title 展示），
 * 改动时最容易"改一处忘一处"，单测能立刻发现。
 */
class NotificationServiceTest {

    private static final Long MEETING_ID = 100L;
    private static final Long ORGANISER = 4L;

    private NotificationMapper notificationMapper;
    private RecipientMapper recipientMapper;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        notificationMapper = mock(NotificationMapper.class);
        recipientMapper = mock(RecipientMapper.class);
        service = new NotificationService(notificationMapper, recipientMapper, mock(ApplicationEventPublisher.class));
    }

    /** 把本次事件产生的**所有**插入行合起来（服务按"一批收件人一条 INSERT"分批调用） */
    @SuppressWarnings("unchecked")
    private List<Notification> capturedRows() {
        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationMapper, atLeastOnce()).insertBatch(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    private MeetingDomainEvent created(int status) {
        return MeetingDomainEvent.created(MEETING_ID, "产品评审会", 6L, ORGANISER,
                LocalDateTime.of(2026, 11, 20, 10, 0), LocalDateTime.of(2026, 11, 20, 11, 0), status);
    }

    @Test
    void freeRoomCreationNotifiesOrganiserOnly() {
        service.handle(created(20));

        List<Notification> rows = capturedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUserId()).isEqualTo(ORGANISER);
        assertThat(rows.get(0).getType()).isEqualTo("MEETING_CREATED");
        assertThat(rows.get(0).getTitle()).isEqualTo("预约成功");
        assertThat(rows.get(0).getReadFlag()).isNull(); // 落库时由 SQL 写 0，实体上不设
    }

    @Test
    void approvalRoomCreationAlsoNotifiesApprovers() {
        when(recipientMapper.selectApproverIds()).thenReturn(List.of(8L, 9L));

        service.handle(created(10));

        List<Notification> rows = capturedRows();
        assertThat(rows).extracting(Notification::getUserId).containsExactlyInAnyOrder(4L, 8L, 9L);
        assertThat(rows).filteredOn(n -> n.getType().equals("APPROVAL_TODO"))
                .extracting(Notification::getUserId).containsExactlyInAnyOrder(8L, 9L);
        assertThat(rows).filteredOn(n -> n.getType().equals("MEETING_CREATED"))
                .singleElement().satisfies(n -> assertThat(n.getTitle()).isEqualTo("预约已提交"));
    }

    @Test
    void longApprovalCommentShouldBeClampedToColumnWidth() {
        String comment = "意".repeat(600); // meeting_approval.comment 上限 500，但通知拼上前后缀后还会更长
        service.handle(MeetingDomainEvent.approved(MEETING_ID, "产品评审会", 6L, ORGANISER,
                LocalDateTime.of(2026, 11, 20, 10, 0), LocalDateTime.of(2026, 11, 20, 11, 0), 9L, comment));

        List<Notification> rows = capturedRows();
        assertThat(rows).hasSize(1);
        // 列宽 500：超了就是 1406 Data too long，一次事件写不进去会连整批回滚（踩坑 #10 的同类场景）
        assertThat(rows.get(0).getContent().length()).isLessThanOrEqualTo(500);
        assertThat(rows.get(0).getTitle()).isEqualTo("审批通过");
    }

    @Test
    void cancelNotifiesParticipantsOnly() {
        when(recipientMapper.selectParticipantIds(MEETING_ID)).thenReturn(List.of(5L, 6L));

        service.handle(MeetingDomainEvent.cancelled(MEETING_ID, "产品评审会", 6L, ORGANISER,
                LocalDateTime.of(2026, 11, 20, 10, 0), LocalDateTime.of(2026, 11, 20, 11, 0), "议题变更"));

        List<Notification> rows = capturedRows();
        assertThat(rows).extracting(Notification::getUserId).containsExactlyInAnyOrder(5L, 6L);
        assertThat(rows).allSatisfy(n -> {
            assertThat(n.getType()).isEqualTo("MEETING_CANCELLED");
            assertThat(n.getContent()).contains("议题变更");
        });
    }

    @Test
    void remindGoesToOrganiserUnionParticipantsWithoutDuplicates() {
        // 组织人同时也在参会人里：必须只发一条（否则同一个人收到两遍同样的提醒）
        when(recipientMapper.selectParticipantIds(MEETING_ID)).thenReturn(List.of(ORGANISER, 5L));

        service.handle(MeetingDomainEvent.remind(MEETING_ID, "产品评审会", 6L, ORGANISER,
                LocalDateTime.of(2026, 11, 20, 10, 0), LocalDateTime.of(2026, 11, 20, 11, 0), 20));

        List<Notification> rows = capturedRows();
        assertThat(rows).extracting(Notification::getUserId).containsExactlyInAnyOrder(4L, 5L);
        assertThat(rows).allSatisfy(n -> assertThat(n.getType()).isEqualTo("MEETING_REMIND"));
    }

    @Test
    void noRecipientShouldSkipInsertEntirely() {
        when(recipientMapper.selectParticipantIds(MEETING_ID)).thenReturn(List.of());

        service.handle(MeetingDomainEvent.cancelled(MEETING_ID, "产品评审会", 6L, ORGANISER,
                LocalDateTime.of(2026, 11, 20, 10, 0), LocalDateTime.of(2026, 11, 20, 11, 0), null));

        // 空 IN 列表的批量插入会拼出语法错误的 SQL——没有收件人时必须**整条不发**
        verify(notificationMapper, never()).insertBatch(any());
    }

    @Test
    void nullTypeShouldThrowSoPoisonMessageGoesToDlq() {
        // 消费端（MeetingEventConsumer）依赖这个异常把结构非法的消息送进死信——
        // 如果这里改成"记个日志就返回"，毒消息会被 ack 掉、静默消失（验证脚本断言的正是"进 DLQ"）
        MeetingDomainEvent noType = new MeetingDomainEvent(null, MEETING_ID, "x", 6L, ORGANISER,
                LocalDateTime.of(2026, 11, 20, 10, 0), LocalDateTime.of(2026, 11, 20, 11, 0), 20, null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.handle(noType))
                .isInstanceOf(IllegalArgumentException.class);
        verify(notificationMapper, never()).insertBatch(any());
    }
}
