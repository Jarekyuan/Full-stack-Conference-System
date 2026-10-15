package com.smt.meeting.event;

/**
 * 会议领域事件类型（T4.1）。
 *
 * <p>每个类型对应一个 MQ 路由键，命名见 docs/02 5.2 与 docs/04 6.2 的通知场景表：
 * {@code meeting.created} / {@code meeting.approved} / {@code meeting.rejected} /
 * {@code meeting.cancelled} / {@code meeting.updated} / {@code meeting.remind}。
 *
 * <p>用枚举而不是裸字符串：路由键是**生产端与消费端之间的契约**，
 * 拼错一个字母不会编译报错、只会让消息静默进不了任何队列（topic exchange 的路由键不匹配就是丢弃）。
 */
public enum MeetingEventType {

    /** 创建预约（免审 → 20，需审 → 10） */
    CREATED("meeting.created"),
    /** 审批通过 */
    APPROVED("meeting.approved"),
    /** 审批拒绝 */
    REJECTED("meeting.rejected"),
    /** 取消（组织人取消 / 30 进行中禁止） */
    CANCELLED("meeting.cancelled"),
    /** 修改（时间/会议室/标题/备注/参会人） */
    UPDATED("meeting.updated"),
    /** 会议提醒（T4.5 延迟消息，提前 15 分钟） */
    REMIND("meeting.remind");

    private final String routingKey;

    MeetingEventType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
