package com.smt.notification.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 通知列表项（docs/04 3.3 补登的契约）。
 *
 * <p>**不返回 userId**：列表本来就是"我的通知"，返回它没有意义，还多一个字段可被误用。
 * 字段与 WS 推送体（docs/04 6.2）保持一致——前端列表项与实时推送用同一套渲染逻辑。
 */
@Data
public class NotificationVO {

    private Long id;
    /** 类型（前端映射文案/图标），取值见 docs/04 5.3 */
    private String type;
    private String title;
    private String content;
    /** 关联会议（点通知跳详情），可能为空 */
    private Long meetingId;
    /** 0 未读 / 1 已读 */
    private Integer readFlag;
    private LocalDateTime createdAt;
}
