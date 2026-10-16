package com.smt.common.mq;

/**
 * 会议领域事件类型（T4.1）。
 *
 * <p>每个类型对应一个 MQ 路由键，命名见 docs/02 5.2 与 docs/04 6.2 的通知场景表：
 * {@code meeting.created} / {@code meeting.approved} / {@code meeting.rejected} /
 * {@code meeting.cancelled} / {@code meeting.updated} / {@code meeting.remind}。
 *
 * <p>用枚举而不是裸字符串：路由键是**生产端与消费端之间的契约**，
 * 拼错一个字母不会编译报错、只会让消息静默进不了任何队列（topic exchange 的路由键不匹配就是丢弃）。
 *
 * <p>为什么这个枚举在 common 而不是 meeting-service（T4.3 迁）：它是**跨服务契约**，
 * 消费端（notification-service）也要用。放在生产端模块里，消费端就只能各写一份——
 * 哪天有人改了路由键，生产端改了、消费端没改，结果是消息投进交换机的"某种"队列、
 * 消费端一个都收不到，而且**没有任何编译错误**。放共享库里，两端引用同一份，改名时编译器帮你找齐。
 */
public enum MeetingEventType {

    /** 创建预约（免审 → 20，需审 → 10） */
    CREATED(MeetingMqTopology.QUEUE_CREATED),
    /** 审批通过 */
    APPROVED(MeetingMqTopology.QUEUE_APPROVED),
    /** 审批拒绝 */
    REJECTED(MeetingMqTopology.QUEUE_REJECTED),
    /** 取消（组织人取消 / 30 进行中禁止） */
    CANCELLED(MeetingMqTopology.QUEUE_CANCELLED),
    /** 修改（时间/会议室/标题/备注/参会人） */
    UPDATED(MeetingMqTopology.QUEUE_UPDATED),
    /** 会议提醒（T4.5 延迟消息，提前 15 分钟） */
    REMIND(MeetingMqTopology.QUEUE_REMIND);

    private final String routingKey;

    MeetingEventType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
