package com.smt.notification.entity;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 站内通知（对应表 notification，字段见 db/01-schema.sql）。
 *
 * <p>⚠️ 列宽与用户可控内容的关系（踩坑 #10）：`title` 是 100、`content` 是 500。
 * 通知文案里嵌了**会议标题（≤100）**和**审批意见（≤500）**，拼起来必然可能超宽，
 * 而 MySQL 严格模式下超宽是 `1406 Data too long` 直接报错——一条通知写不进去，
 * 会把整批通知连事务一起回滚。截断统一在 {@code NotificationService} 里做（内容列宽的所有者）。
 */
@Data
public class Notification {

    private Long id;
    /** 接收人（sys_user.id） */
    private Long userId;
    /** 类型，取值见 docs/04 5.3：MEETING_CREATED/APPROVAL_TODO/APPROVAL_RESULT/MEETING_REMIND/MEETING_UPDATED/MEETING_CANCELLED */
    private String type;
    private String title;
    private String content;
    /** 关联会议（前端点击跳转详情），可空 */
    private Long meetingId;
    /** 0 未读 / 1 已读 */
    private Integer readFlag;
    private LocalDateTime createdAt;
}
