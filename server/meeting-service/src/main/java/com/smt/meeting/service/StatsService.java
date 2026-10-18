package com.smt.meeting.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.mapper.StatsMapper;
import com.smt.meeting.vo.RoomUsageRow;
import com.smt.meeting.vo.MyMeetingStatsVO;
import com.smt.meeting.vo.RoomUsageVO;

/**
 * 统计服务（T5.3：H-1 会议室使用率 / H-2 个人会议统计；口径见 docs/04 4.10）。
 *
 * <h3>为什么把"口径"写得这么细</h3>
 * 统计类接口最容易出的不是 SQL 写错，而是**口径各算各的**：前端的柱状图按"会议数"排序、
 * 后端的占用率按"裁剪后的时长"算，两边都"对"，图却解释不通。所以：
 * 计入哪些状态、跨区间怎么裁、可用分钟怎么定、占用率的量纲，全部在文档里写死，代码严格照做。
 *
 * <h3>为什么全部在 SQL 里聚合</h3>
 * "按会议室 group by + 时间区间裁剪"是数据库最擅长的事（`idx_room_time` 直接服务）；
 * 拉到内存再用 Java 分组，等于把数据量问题留给应用（会议多了就是一次全表拉取）。
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    /** 每天可用分钟（08:00–20:00）：见 docs/04 4.10 的口径表 */
    private static final int MINUTES_PER_DAY = 720;
    /** 个人统计的窗口（H-2：近 30 天） */
    private static final int MY_STATS_DAYS = 30;

    private final StatsMapper statsMapper;

    /**
     * 会议室使用率（H-1，仅 ADMIN——权限在 Controller 上用 @PreAuthorize 收口）。
     *
     * @param start 起始日期（含）
     * @param end   结束日期（含）；缺失时默认"今天往前 30 天"（前端首次进入看板不必传参）
     */
    public RoomUsageVO roomUsage(LocalDate start, LocalDate end) {
        LocalDate today = LocalDate.now();
        LocalDate from = start != null ? start : today.minusDays(MY_STATS_DAYS - 1L);
        LocalDate to = end != null ? end : today;
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "起始日期不能晚于结束日期");
        }
        long days = to.toEpochDay() - from.toEpochDay() + 1;
        // O17：防止 ADMIN 传 0001~9999 让聚合无意义地扫全表
        if (days > 366) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "统计区间最多 366 天");
        }
        long availableMinutes = days * MINUTES_PER_DAY;

        // 半开区间 [from 00:00, to+1 00:00)：区间端点用"日期"解释，含 end 全天
        LocalDateTime rangeStart = from.atStartOfDay();
        LocalDateTime rangeEnd = to.plusDays(1).atStartOfDay();

        // 当前会议室 + 已删除会议室的历史（R8）；已删除房间用 `已删除会议室#id` 兜底名称，
        // 否则管理员删掉旧房间后，它上个月的使用率会静默消失。
        List<RoomUsageRow> rows = new ArrayList<>(statsMapper.selectRoomUsage(rangeStart, rangeEnd));
        rows.addAll(statsMapper.selectDeletedRoomUsage(rangeStart, rangeEnd));
        List<RoomUsageVO.Item> items = rows.stream()
                .map(row -> new RoomUsageVO.Item(row.getRoomId(), row.getRoomName(), row.getMeetingCount(),
                        row.getTotalMinutes(),
                        // 占用率给 0~1 的小数（量纲写在文档里；四舍五入到 4 位，避免 0.00805555… 这种噪声）
                        Math.round(row.getTotalMinutes() * 10000.0 / availableMinutes) / 10000.0))
                .sorted(Comparator.comparing(RoomUsageVO.Item::roomName))
                .toList();
        long totalMinutes = items.stream().mapToLong(RoomUsageVO.Item::totalMinutes).sum();
        return new RoomUsageVO(from, to, availableMinutes, totalMinutes, items);
    }

    /**
     * 个人会议统计（H-2：近 30 天，我组织的 ∪ 我参与的）。
     *
     * <p>窗口固定"近 30 天"而不是让前端传：H-2 说的就是"趋势"，
     * 开放任意区间会让这个接口变成"第二个 room-usage"，前端反而不知道该展示什么。
     */
    public MyMeetingStatsVO myMeetings(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(MY_STATS_DAYS - 1L);
        List<MyMeetingStatsVO.Daily> daily = statsMapper.selectMyDailyStats(
                userId, from.atStartOfDay(), today.plusDays(1).atStartOfDay());
        long totalMeetings = daily.stream().mapToLong(MyMeetingStatsVO.Daily::count).sum();
        long totalMinutes = daily.stream().mapToLong(MyMeetingStatsVO.Daily::minutes).sum();
        return new MyMeetingStatsVO(MY_STATS_DAYS, totalMeetings, totalMinutes, daily);
    }
}
