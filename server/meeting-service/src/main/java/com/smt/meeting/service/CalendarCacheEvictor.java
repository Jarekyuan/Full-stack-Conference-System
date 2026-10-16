package com.smt.meeting.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 日历缓存失效（T4.7）：按"会议室 + 时段"删掉受影响的周缓存。
 *
 * <p>抽成独立组件的理由（2026-10-16 评审 R3）：失效有**两个调用方**——
 * ① 领域事件监听（{@code AvailabilityService.onMeetingChanged}，覆盖新建/取消/审批/改期的新时段）；
 * ② 改期时**旧时段**（事件里只有新值，旧房间/旧周那份缓存不删就会留着一场"已经搬走"的会）。
 * 两处各写一遍删 key 的逻辑，迟早一处改一处忘；放在这里，"周次怎么算"只有一份实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalendarCacheEvictor {

    /** docs/02 6.4 的键设计：cache:calendar:{roomId}:{weekStart} */
    public static final String CALENDAR_KEY_PREFIX = "cache:calendar:";

    private final StringRedisTemplate redis;

    /**
     * 删除覆盖 [start, end] 的周缓存（会议可能横跨两周，两边的 key 都要删）。
     * 缓存是旁路：Redis 故障只告警，绝不影响业务。
     */
    public void evict(Long roomId, LocalDateTime start, LocalDateTime end) {
        if (roomId == null || start == null || end == null) {
            return;
        }
        try {
            LocalDate startWeek = weekStart(start);
            LocalDate endWeek = weekStart(end);
            redis.delete(CALENDAR_KEY_PREFIX + roomId + ":" + startWeek);
            if (!endWeek.equals(startWeek)) {
                redis.delete(CALENDAR_KEY_PREFIX + roomId + ":" + endWeek);
            }
        } catch (Exception e) {
            log.warn("[缓存] 删除日历缓存失败（缓存会自然过期）: roomId={}, err={}", roomId, e.getMessage());
        }
    }

    /** 该日期所在周的周一（日历接口按周缓存，week 传任意一天都归一化到周一） */
    private LocalDate weekStart(LocalDateTime time) {
        return time.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
