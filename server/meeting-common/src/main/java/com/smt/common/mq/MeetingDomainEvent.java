package com.smt.common.mq;

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
 * 事件只描述"发生了什么事"，收件人由消费端按 meeting_id 查当时的库。
 *
 * <p>为什么不塞 roomName/organiserName：发布点（取消/审批）手上没有这些值，
 * 为了拼消息再查一次库，等于让"通知"这种非核心链路反向拖慢核心写路径。
 * "自包含"指的是**不需要回调生产服务**，不是"不能查库"。
 *
 * <p><b>status（T4.3 补）</b>：事件发生**之后**会议所处的状态。加这个字段是因为"要不要通知审批人"
 * 取决于会议落没落进待审批（10）：免审房创建是 20，审批房创建是 10，修改到审批房（R-14）也会回 10。
 * 没有它，消费端为了判断这件事得再回查一次库——而那次回查读到的可能是**已经被审批之后**的状态，
 * 于是"该发的待审批通知"被静默吞掉。事件带上"当时的状态"，判断才和事实一致。
 *
 * <p>⚠️ 类位置（T4.3）：本类与 {@link MeetingEventMessage} 一起放在 meeting-common，不留在 meeting-service。
 * 原因是它**上了 MQ**——生产端与消费端必须按同一份契约读写字段，放一端就会漂移，
 * 而漂移的后果（字段对不上、反序列化出来是 null）在编译期完全看不见。
 */
public record MeetingDomainEvent(
        MeetingEventType type,
        Long meetingId,
        String title,
        Long roomId,
        Long organiserId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        /** 事件发生后会议的状态（10 待审批 / 20 已预约 / 50 已取消 / 60 已拒绝） */
        Integer status,
        /** 操作人：审批类事件是审批人，取消是组织人；系统任务为 null */
        Long operatorId,
        /** 审批意见 / 取消原因（按事件类型取用） */
        String comment) {

    /** 创建预约：status = 10（需审批）或 20（免审批） */
    public static MeetingDomainEvent created(Long meetingId, String title, Long roomId, Long organiserId,
                                             LocalDateTime startTime, LocalDateTime endTime, Integer status) {
        return new MeetingDomainEvent(MeetingEventType.CREATED, meetingId, title, roomId, organiserId,
                startTime, endTime, status, organiserId, null);
    }

    /** 审批结果：operatorId = 审批人，comment = 审批意见 */
    public static MeetingDomainEvent approved(Long meetingId, String title, Long roomId, Long organiserId,
                                              LocalDateTime startTime, LocalDateTime endTime,
                                              Long approverId, String comment) {
        return new MeetingDomainEvent(MeetingEventType.APPROVED, meetingId, title, roomId, organiserId,
                startTime, endTime, 20, approverId, comment);
    }

    public static MeetingDomainEvent rejected(Long meetingId, String title, Long roomId, Long organiserId,
                                              LocalDateTime startTime, LocalDateTime endTime,
                                              Long approverId, String comment) {
        return new MeetingDomainEvent(MeetingEventType.REJECTED, meetingId, title, roomId, organiserId,
                startTime, endTime, 60, approverId, comment);
    }

    /** 取消：comment = 取消原因（可空）；operatorId 默认组织人 */
    public static MeetingDomainEvent cancelled(Long meetingId, String title, Long roomId, Long organiserId,
                                               LocalDateTime startTime, LocalDateTime endTime, String reason) {
        return cancelled(meetingId, title, roomId, organiserId, startTime, endTime, reason, organiserId);
    }

    /**
     * 取消（显式操作人）：ADMIN 代取消时 operatorId ≠ organiserId，
     * 通知服务据此把组织人也加进收件人（O15）。
     */
    public static MeetingDomainEvent cancelled(Long meetingId, String title, Long roomId, Long organiserId,
                                               LocalDateTime startTime, LocalDateTime endTime,
                                               String reason, Long operatorId) {
        return new MeetingDomainEvent(MeetingEventType.CANCELLED, meetingId, title, roomId, organiserId,
                startTime, endTime, 50, operatorId, reason);
    }

    /**
     * 修改：status = 修改后的状态（改到需审批会议室时 R-14 会回 10，其余仍 20）。
     *
     * <p>事件里只有**新值**没有旧值——所以消费端没法自己判断"改了什么"，
     * "会议信息有变更"这类文案按 docs/04 5.3 的通知类型（MEETING_UPDATED）写即可。
     */
    public static MeetingDomainEvent updated(Long meetingId, String title, Long roomId, Long organiserId,
                                             LocalDateTime startTime, LocalDateTime endTime, Integer status,
                                             Long operatorId) {
        return new MeetingDomainEvent(MeetingEventType.UPDATED, meetingId, title, roomId, organiserId,
                startTime, endTime, status, operatorId, null);
    }

    /**
     * 会议即将开始（T4.5 提醒，由定时扫描在"开始前 15 分钟窗口"内发出）。
     *
     * <p>status 取会议**当时的真实状态**（扫描出来的多是 20；30 已经开过的不会被扫到，
     * 因为窗口条件是 `start_time > now`）。不写死一个常量：事件字段一旦说谎，
     * 消费端基于它做的判断就全是错的（哪怕当前没人用）。
     */
    public static MeetingDomainEvent remind(Long meetingId, String title, Long roomId, Long organiserId,
                                           LocalDateTime startTime, LocalDateTime endTime, Integer status) {
        return new MeetingDomainEvent(MeetingEventType.REMIND, meetingId, title, roomId, organiserId,
                startTime, endTime, status, null, null);
    }

    /** 是否待审批（消费端据此决定要不要通知审批人） */
    public boolean pendingApproval() {
        return status != null && status == 10;
    }
}
