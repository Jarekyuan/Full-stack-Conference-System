package com.smt.meeting.enums;

import lombok.Getter;

/**
 * 会议状态（对照 docs/01 4.5 状态表 / docs/04 5.1）
 * 6 个状态：10/20/30/40/50/60；40/50/60 为终态
 */
@Getter
public enum MeetingStatusEnum {

    PENDING_APPROVAL(10, "待审批"),
    RESERVED(20, "已预约"),
    ONGOING(30, "进行中"),
    FINISHED(40, "已结束"),
    CANCELLED(50, "已取消"),
    REJECTED(60, "已拒绝");

    private final int code;
    private final String label;

    MeetingStatusEnum(int code, String label) {
        this.code = code;
        this.label = label;
    }
}
