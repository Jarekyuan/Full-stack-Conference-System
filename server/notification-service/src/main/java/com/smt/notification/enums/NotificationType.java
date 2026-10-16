package com.smt.notification.enums;

/**
 * 通知类型（值见 docs/04 5.3 通知类型表；存进 `notification.type` VARCHAR(30)）。
 *
 * <p>用枚举的 name() 落库而不是散字符串：前端按这个值映射文案与图标，
 * 拼错一个字母不会报错，只会让前端"这条通知不认识、显示不出来"。
 */
public enum NotificationType {

    /** 预约创建成功 → 组织人 */
    MEETING_CREATED,
    /** 有新的待审批 → 审批人 */
    APPROVAL_TODO,
    /** 审批通过/拒绝 → 组织人 */
    APPROVAL_RESULT,
    /** 开始前 15 分钟提醒 → 参会人（T4.5） */
    MEETING_REMIND,
    /** 会议时间/信息变更 → 参会人 */
    MEETING_UPDATED,
    /** 会议取消 → 参会人 */
    MEETING_CANCELLED
}
