package com.smt.meeting.service;

import com.smt.meeting.dto.AvailabilityQueryDTO;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.vo.AvailabilityVO;
import com.smt.meeting.vo.CalendarMeetingVO;
import com.smt.meeting.vo.MeetingConflictVO;
import com.smt.meeting.vo.RoomCalendarVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 可用性与日历查询（docs/04 4.3）
 * 边界说明：只做"会议室维度"的可用性查询，不做"参会人忙闲"（01 文档已砍该需求）。
 * 只读无锁：实时提示用，最终以提交时（创建/审批）的锁内校验为准——"查询与提交分离"（docs/04 7.4）。
 */
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final ConflictChecker conflictChecker;
    private final MeetingMapper meetingMapper;

    /**
     * 批量可用性：逐会议室调冲突检测（无锁），组装 available + conflicts。
     * `distinct()` 去重：重复 id 只会得到两条内容完全相同的结论，却要多付一次查询（评审 R7）。
     */
    public AvailabilityVO check(AvailabilityQueryDTO dto) {
        List<AvailabilityVO.Item> items = dto.getRoomIds().stream()
                .distinct()
                .map(roomId -> {
                    List<MeetingConflictVO> conflicts = conflictChecker.findConflicts(
                            roomId, dto.getStartTime(), dto.getEndTime(), dto.getExcludeMeetingId());
                    return new AvailabilityVO.Item(roomId, conflicts.isEmpty(), conflicts);
                })
                .toList();
        return new AvailabilityVO(items);
    }

    /**
     * 单会议室某周日历：week 传该周任意一天，归一化到周一起的 7 天，补空天后返回。
     */
    public RoomCalendarVO calendar(Long roomId, LocalDate week) {
        LocalDate weekStart = week.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);

        // 查询范围为 [周一 00:00, 下周一 00:00)（半开区间）
        List<CalendarMeetingVO> meetings = meetingMapper.selectByRoomAndRange(
                roomId, weekStart.atStartOfDay(), weekStart.plusDays(7).atStartOfDay());

        // 按开始日期分组（TreeMap 保证日期升序）
        Map<LocalDate, List<CalendarMeetingVO>> byDate = meetings.stream()
                .collect(Collectors.groupingBy(m -> m.getStartTime().toLocalDate(),
                        TreeMap::new, Collectors.toList()));

        // 补齐 7 天：空的天也给空数组，前端网格按索引直接渲染
        List<RoomCalendarVO.Day> days = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            LocalDate date = weekStart.plusDays(i);
            days.add(new RoomCalendarVO.Day(date, byDate.getOrDefault(date, List.of())));
        }

        RoomCalendarVO vo = new RoomCalendarVO();
        vo.setRoomId(roomId);
        vo.setWeekStart(weekStart);
        vo.setWeekEnd(weekEnd);
        vo.setDays(days);
        return vo;
    }
}
