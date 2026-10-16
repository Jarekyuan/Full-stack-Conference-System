package com.smt.meeting.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Data;

/**
 * 周期会议规则（对应表 meeting_recurring，字段见 db/01-schema.sql）。
 *
 * <p>与 {@code meeting.recurring_id} 的关系：本表存**规则**（不是场次），
 * 展开出来的每一场会议在 meeting 表里是独立的一行、用 recurring_id 回指本表。
 * 这么设计的好处：单场改期/取消、状态机、冲突检测这些逻辑**完全不用改**——
 * 系列只是"一批共享同一规则的普通会议"，而不是一种新的会议类型（01 文档流程 5 的第 3 条）。
 */
@Data
public class MeetingRecurring {

    private Long id;
    private Long organiserId;
    private Long roomId;
    private String title;
    /** 每周第几天：1=周一 … 7=周日（与 java.time.DayOfWeek.getValue() 一致） */
    private Integer dayOfWeek;
    /** 开始时间（当天分钟数，如 540=09:00）：与"时刻"解耦，跨时区/跨夏令时都不用重算 */
    private Integer startMinutes;
    private Integer durationMinutes;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDateTime createdAt;
}
