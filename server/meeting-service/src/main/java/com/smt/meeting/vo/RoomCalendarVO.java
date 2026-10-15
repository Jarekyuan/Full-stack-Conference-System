package com.smt.meeting.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 单会议室某周日历（对照 docs/04 3.2：GET /rooms/{id}/calendar?week=）
 * 结构说明：04 文档只登记了接口、未定响应结构，此处按"按天分组 + 补齐 7 天"设计
 * （空的天也在 days 里，前端网格直接按索引渲染，无需自己做补位）。
 */
@Data
public class RoomCalendarVO {

    private Long roomId;
    /** 归一化后的周一 */
    private LocalDate weekStart;
    /** 周日 */
    private LocalDate weekEnd;
    /** 固定 7 项（周一 → 周日） */
    private List<Day> days;

    @Data
    @AllArgsConstructor
    public static class Day {
        private LocalDate date;
        /** 当天会议（按开始时间升序；无会议为空数组） */
        private List<CalendarMeetingVO> meetings;
    }
}
