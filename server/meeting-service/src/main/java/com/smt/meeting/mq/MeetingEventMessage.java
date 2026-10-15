package com.smt.meeting.mq;

import java.time.LocalDateTime;

import com.smt.meeting.event.MeetingDomainEvent;

/**
 * MQ 消息体（T4.1）：领域事件外面再包一层"消息元数据"。
 *
 * <p>为什么要分开：`msgId` / `occurredAt` 是**消息传输层**的概念（幂等键、时序判断），
 * 而 {@link MeetingDomainEvent} 是**业务语义**（谁改了哪场会）。混成一个类会让业务事件
 * 被迫携带"为了去重而生"的字段，消费端也难判断哪些字段是业务事实、哪些是传输信息。
 *
 * <p>`msgId` 是消费端幂等去重的键（T4.3 用 SETNX 去重）：MQ 的投递语义是 at-least-once，
 * 同一条消息**一定会**重复投递（消费者 ack 前断连、网络抖动重投），所以去重是消费端的责任，不是可选项。
 */
public record MeetingEventMessage(String msgId, LocalDateTime occurredAt, MeetingDomainEvent data) {

    public static MeetingEventMessage of(String msgId, MeetingDomainEvent data) {
        return new MeetingEventMessage(msgId, LocalDateTime.now(), data);
    }

    /** 路由键（= 事件类型的 routingKey；消息体自带一份，便于消费端排障时不依赖信封属性） */
    public String routingKey() {
        return data.type().routingKey();
    }
}
