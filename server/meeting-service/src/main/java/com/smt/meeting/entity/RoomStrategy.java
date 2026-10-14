package com.smt.meeting.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会议室预约策略（对应表 room_strategy，1:1 于会议室，字段见 docs/03 数据库设计）
 */
@Data
public class RoomStrategy {

    private Long id;
    private Long roomId;
    /** 是否需审批：0 免审 / 1 需审批 */
    private Integer needApproval;
    /** 最多提前预约天数 */
    private Integer advanceDays;
    /** 单场最长时长（分钟） */
    private Integer maxDurationMinutes;
    /** 取消截止：距开始不足 N 分钟不可取消 */
    private Integer cancelDeadlineMinutes;
    /** 锁定缓冲：会议前后各 N 分钟不排会（R-13） */
    private Integer bufferMinutes;
    /** 是否允许周期会议：0 否 / 1 是 */
    private Integer allowRecurring;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
