package com.smt.meeting.vo;

import java.time.LocalDate;
import java.util.List;

/**
 * 批量日历响应（T6.3）：一次返回多个会议室的周日程。
 *
 * <p>房间维度仍然是**按房间缓存**（cache:calendar:{roomId}:{weekStart}），这里只是把
 * N 个单房间结果聚合成一个 HTTP 响应，避免前端一次加载发 N 个请求。
 * `failedRoomIds` 保留“逐房间容错”的语义：某个房间查询失败不会让整周空白。
 */
public record RoomCalendarsVO(
        LocalDate weekStart,
        LocalDate weekEnd,
        List<RoomCalendarVO> items,
        /** 查询失败/被跳过的房间（前端对这些房间显示告警，其余照常渲染） */
        List<Long> failedRoomIds) {
}
