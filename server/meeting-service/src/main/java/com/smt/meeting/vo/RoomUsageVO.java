package com.smt.meeting.vo;

import java.time.LocalDate;
import java.util.List;

/**
 * 会议室使用率（T5.3 / H-1，契约 docs/04 4.10）。
 *
 * <p>为什么同时给"总时长"和"占用率"：前端柱状图想按哪个画都行；
 * 只给占用率的话，"这个会议室 0.3% 是不是数据错了"没法自查（900 分钟 vs 0.0125 一眼看得出）。
 */
public record RoomUsageVO(LocalDate start, LocalDate end, long availableMinutes, long totalMinutes,
                          List<Item> items) {

    public record Item(Long roomId, String roomName, long meetingCount, long totalMinutes, double usageRate) {
    }
}
