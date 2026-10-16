package com.smt.meeting.vo;

import java.time.LocalDate;
import java.util.List;

/**
 * 个人会议统计（T5.3 / H-2，契约 docs/04 4.10）。
 *
 * <p>{@code daily} 只含**有会议的日期**：补零是展示层的事，接口给事实（否则"哪天真的有会"就看不出来了）。
 */
public record MyMeetingStatsVO(int days, long totalMeetings, long totalMinutes, List<Daily> daily) {

    public record Daily(LocalDate date, long count, long minutes) {
    }
}
