package com.smt.meeting.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 日历网格的会议条目（供前端 CalendarGrid 渲染）。
 * status 用于按状态配色（docs/04 5.1：10 橙 / 20 蓝 / 30 绿）。
 */
@Data
public class CalendarMeetingVO {

    private Long meetingId;
    private String title;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 10 待审批 / 20 已预约 / 30 进行中 */
    private Integer status;
    private Long organiserId;
    private String organiserName;
}
