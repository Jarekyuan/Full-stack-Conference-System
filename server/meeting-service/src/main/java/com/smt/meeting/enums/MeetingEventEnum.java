package com.smt.meeting.enums;

import lombok.Getter;

/**
 * 会议状态变更事件（对照 docs/01 4.5 迁移矩阵的列）
 * 事件名与 meeting_status_log.event 列的取值一致（VARCHAR 存 name()）
 *
 * 说明：MODIFY（修改会议触发审批分流重判，R-14）在 T2.10 引入时扩展。
 */
@Getter
public enum MeetingEventEnum {

    /** 创建预约（免审 → 20，需审 → 10，由会议室策略决定） */
    RESERVE("创建预约"),
    /** 审批通过：10 → 20 */
    APPROVE("审批通过"),
    /** 审批拒绝：10 → 60 */
    REJECT("审批拒绝"),
    /** 取消：10/20 → 50（30 进行中禁止） */
    CANCEL("取消会议"),
    /** 开始（定时任务）：20 → 30 */
    START("会议开始"),
    /** 结束（定时任务或组织人提前结束）：20/30 → 40 */
    END("会议结束"),
    /** 延长（ONGOING 特例：只改 end_time 不改状态，M3 实现时补迁移规则） */
    EXTEND("延长会议"),
    /** 修改会议触发审批分流重判（R-14）：20 → 10（改到需审批会议室时，T2.10） */
    MODIFY("修改会议");

    private final String label;

    MeetingEventEnum(String label) {
        this.label = label;
    }
}
