package com.smt.meeting.event;

import java.time.LocalDateTime;

/**
 * 会议领域事件（T4.1）：业务代码在**事务内**发布的 Spring 事件，
 * 由 {@code MeetingEventPublisher} 在**事务提交后**转投 MQ（docs/02 5.2 第 7 步）。
 *
 * <p>为什么业务代码不直接发 MQ：
 * 发 MQ 是不可回滚的副作用，而"发消息"与"落库"之间只要不是同一个提交点，就必然有一种坏情况——
 * 先发后落库：事务回滚了消息还在（下游凭空收到一场不存在的会议）；
 * 先落库后发：发送失败消息就丢了（下游永远收不到）。
 * 把"发"推迟到 AFTER_COMMIT，至少保证**不会发出不存在的事实**；
 * 极端情况下（提交成功但进程立刻崩溃、消息未发出）会漏发，这属于 MQ 的可靠投递范畴，
 * 本项目的处理是"接受漏发 + 有对账任务兜"（见 docs/02 5.2 的取舍说明）。
 *
 * <p>为什么不塞参会人列表：消费端要通知"当前参会人"，而参会人**是可以被改的**——
 * 事件里带一份快照，一旦与库里的现状不一致，通知就会发给已经被移出的人（或漏掉新加的人）。
 * 事件只描述"发生了什么事"，收件人由消费端按 meeting_id 查当时的库（两边共库，一次 JOIN 的事）。
 *
 * <p>为什么不塞 roomName/organiserName：发布点（取消/审批）手上没有这些值，
 * 为了拼消息再查一次库，等于让"通知"这种非核心链路反向拖慢核心写路径。
 * 消费端（notification-service）与 meeting-service 共用同一个库，JOIN 一下就有——
 * "自包含"指的是**不需要回调生产服务**，不是"不能查库"。
 */
public record MeetingDomainEvent(
        MeetingEventType type,
        Long meetingId,
        String title,
        Long roomId,
        Long organiserId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        /** 操作人：审批类事件是审批人，取消是组织人；系统任务为 null */
        Long operatorId,
        /** 审批意见 / 取消原因（按事件类型取用） */
        String comment) {

    /** 创建预约 */
    public static MeetingDomainEvent created(Long meetingId, String title, Long roomId, Long organiserId,
                                             LocalDateTime startTime, LocalDateTime endTime) {
        return new MeetingDomainEvent(MeetingEventType.CREATED, meetingId, title, roomId, organiserId,
                startTime, endTime, organiserId, null);
    }

    /** 审批结果：operatorId = 审批人，comment = 审批意见 */
    public static MeetingDomainEvent approved(Long meetingId, String title, Long roomId, Long organiserId,
                                              LocalDateTime startTime, LocalDateTime endTime,
                                              Long approverId, String comment) {
        return new MeetingDomainEvent(MeetingEventType.APPROVED, meetingId, title, roomId, organiserId,
                startTime, endTime, approverId, comment);
    }

    public static MeetingDomainEvent rejected(Long meetingId, String title, Long roomId, Long organiserId,
                                              LocalDateTime startTime, LocalDateTime endTime,
                                              Long approverId, String comment) {
        return new MeetingDomainEvent(MeetingEventType.REJECTED, meetingId, title, roomId, organiserId,
                startTime, endTime, approverId, comment);
    }

    /** 取消：comment = 取消原因（可空） */
    public static MeetingDomainEvent cancelled(Long meetingId, String title, Long roomId, Long organiserId,
                                               LocalDateTime startTime, LocalDateTime endTime, String reason) {
        return new MeetingDomainEvent(MeetingEventType.CANCELLED, meetingId, title, roomId, organiserId,
                startTime, endTime, organiserId, reason);
    }

    /** 修改：带变化后的状态（要不要通知"时间变了"由消费端按字段比对决定，见 docs/04 6.2） */
    public static MeetingDomainEvent updated(Long meetingId, String title, Long roomId, Long organiserId,
                                             LocalDateTime startTime, LocalDateTime endTime, Long operatorId) {
        return new MeetingDomainEvent(MeetingEventType.UPDATED, meetingId, title, roomId, organiserId,
                startTime, endTime, operatorId, null);
    }
}
