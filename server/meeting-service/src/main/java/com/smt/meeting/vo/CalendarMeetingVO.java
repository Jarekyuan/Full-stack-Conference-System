package com.smt.meeting.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 日历网格的会议条目（供前端 CalendarGrid 渲染）。
 * 日历块的四色口径（T6.1，docs/04 5.1）由前端按 status/organiserId/invited/endTime 判定：
 * 已过期（灰）→ 待审批（橙）→ 我预订（蓝）→ 邀请我的（绿）→ 其他（灰）。
 */
@Data
public class CalendarMeetingVO {

    private Long meetingId;
    private String title;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 10 待审批 / 20 已预约 / 30 进行中 / 40 已结束 */
    private Integer status;
    private Long organiserId;
    private String organiserName;

    /**
     * 当前登录用户是否在这场的参会人里（组织人不算，前端按 organiserId 判“我预订”）。
     *
     * <p>⚠️ 这是**用户相关**字段：日历缓存键是 room+week 共享的，它只在缓存读出后
     * 按请求用户实时装配，**不写回缓存**（见 AvailabilityService#decorateInvited）。
     * 否则 A 用户看到的“邀请我的”会串给 B。
     */
    private Boolean invited;
}
