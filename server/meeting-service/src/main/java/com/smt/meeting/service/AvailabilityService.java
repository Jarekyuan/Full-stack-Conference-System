package com.smt.meeting.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.smt.common.mq.MeetingDomainEvent;
import com.smt.common.mq.MeetingEventType;
import com.smt.meeting.dto.AvailabilityQueryDTO;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingParticipantMapper;
import com.smt.meeting.vo.AvailabilityVO;
import com.smt.meeting.vo.CalendarMeetingVO;
import com.smt.meeting.vo.MeetingConflictVO;
import com.smt.meeting.vo.RoomCalendarVO;

/**
 * 可用性与日历查询（docs/04 4.3）
 * 边界说明：只做"会议室维度"的可用性查询，不做"参会人忙闲"（01 文档已砍该需求）。
 * 只读无锁：实时提示用，最终以提交时（创建/审批）的锁内校验为准——"查询与提交分离"（docs/04 7.4）。
 *
 * <h3>日历缓存（T4.7）为什么手写而不是 {@code @Cacheable}</h3>
 * 它要同时做到四件事，注解一个都表达不了：
 * ① **随机 TTL**（5min ± 抖动）：防缓存**雪崩**——一批 key 同时建、同时过期，会在同一秒集体回源；
 * ② **空值短 TTL**：本周没有会议时也缓存（空对象），但只缓存 1 分钟——防**穿透**（反复查不存在的空周打库），
 *    同时保证"刚订的会议"很快就能出现在日历上；
 * ③ **互斥重建**（single-flight）：miss 时不一起打库，只放一个线程去建，其余短暂等待后读缓存——
 *    防**击穿**（热点 key 过期瞬间的并发回源）；
 * ④ **事件驱动失效**：预约/取消/改期后立刻删对应周的缓存（Cache-Aside：先更库、后删缓存）。
 *
 * <p>缓存**只做加速，不做真相**：任何缓存异常（Redis 挂了、反序列化失败）都退回查库，
 * 绝不让一次缓存故障把日历接口打成 500（旁路依赖不能拖垮核心读路径）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    /** 有数据时的基础 TTL */
    private static final Duration CALENDAR_TTL = Duration.ofMinutes(5);
    /** 抖动幅度（秒）：实际 TTL = 5min ± 这个值 */
    private static final int CALENDAR_TTL_JITTER_SECONDS = 60;
    /** 空周（没有会议）的短 TTL：防穿透，又不能久到"刚订的会看不见" */
    private static final Duration EMPTY_CALENDAR_TTL = Duration.ofMinutes(1);
    /** single-flight 抢锁等待：等不到就自己查库（缓存抢锁**绝不能**把请求挂住） */
    private static final long REBUILD_LOCK_WAIT_MS = 200;
    /** 未抢到重建权的线程：等这么久再读一次缓存（给重建者一点时间） */
    private static final long WAIT_FOR_REBUILD_MS = 150;

    private final ConflictChecker conflictChecker;
    private final MeetingMapper meetingMapper;
    /** 日历的“邀请我的”标记：按请求用户实时查，结果**不写缓存** */
    private final MeetingParticipantMapper meetingParticipantMapper;
    private final StringRedisTemplate redis;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    /** 日历缓存失效（改期时要连"旧房间/旧周"一起删，见 onMeetingChanged 的说明） */
    private final CalendarCacheEvictor cacheEvictor;

    /**
     * 批量可用性：逐会议室调冲突检测（无锁），组装 available + conflicts。
     * `distinct()` 去重：重复 id 只会得到两条内容完全相同的结论，却要多付一次查询（评审 R7）。
     *
     * <p>**不缓存**：可用性是"提交前的实时提示"，且必然带着用户刚输入的时间区间（缓存命中率极低、
     * 过期数据的代价却很高——用户看到"可用"、提交却冲突）。缓存只用在"数据形态固定、读写比极高"
     * 的地方（日历按周查、会议室列表），这是"缓存该不该上"的判断标准。
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
     *
     * <p>{@code userId} 用于给每条会议打 `invited`（当前用户是否参会）。
     * **缓存里存的是未打标的共享数据**，读出来之后才按用户装饰——见 {@link #decorateInvited}。
     */
    public RoomCalendarVO calendar(Long roomId, LocalDate week, Long userId) {
        LocalDate weekStart = week.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        String key = CalendarCacheEvictor.CALENDAR_KEY_PREFIX + roomId + ":" + weekStart;

        RoomCalendarVO cached = readCache(key);
        if (cached != null) {
            return decorateInvited(cached, userId);
        }
        return rebuild(roomId, weekStart, key, userId);
    }

    // ---------- 缓存内部 ----------

    private RoomCalendarVO rebuild(Long roomId, LocalDate weekStart, String key, Long userId) {
        RLock lock = redissonClient.getLock(key + ":rebuild");
        boolean acquired = false;
        try {
            // waitTime 只等 200ms：抢不到就自己查库，**不让缓存把请求挂住**
            acquired = lock.tryLock(REBUILD_LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                // 别人正在重建：稍等再读一次缓存（热点 key 过期瞬间的并发大多能被这一次等待消化）
                Thread.sleep(WAIT_FOR_REBUILD_MS);
                RoomCalendarVO afterWait = readCache(key);
                if (afterWait != null) {
                    return decorateInvited(afterWait, userId);
                }
                log.info("[缓存] 重建等待超时，直接回源: key={}", key);
                return decorateInvited(loadAndCache(roomId, weekStart, key), userId);
            }
            // 双检：等锁期间可能已被别的线程建好
            RoomCalendarVO cached = readCache(key);
            return decorateInvited(cached != null ? cached : loadAndCache(roomId, weekStart, key), userId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return decorateInvited(loadFromDb(roomId, weekStart), userId);
        } catch (Exception e) {
            // 锁/缓存故障一律降级回源（缓存是旁路）
            log.warn("[缓存] 互斥重建异常，回源: key={}, err={}", key, e.getMessage());
            return decorateInvited(loadFromDb(roomId, weekStart), userId);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private RoomCalendarVO loadAndCache(Long roomId, LocalDate weekStart, String key) {
        RoomCalendarVO vo = loadFromDb(roomId, weekStart);
        boolean empty = vo.getDays().stream().allMatch(d -> d.getMeetings().isEmpty());
        Duration ttl = empty ? EMPTY_CALENDAR_TTL : jitteredTtl();
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(vo), ttl);
        } catch (Exception e) {
            // 写缓存失败不影响本次返回（下次再来建）
            log.warn("[缓存] 写入失败: key={}, err={}", key, e.getMessage());
        }
        return vo;
    }

    /**
     * 给缓存/回源结果打上“当前用户是否参会”的标记（T6.1）。
     *
     * <p>**必须在缓存之外做**：日历缓存键是 `room + week` 共享的，用户相关字段一旦写进缓存，
     * 下一个用户就会看到上一个人的“邀请我的”。因此缓存里存的永远是 invited 未赋值的基础数据，
     * 这里只改**本次反序列化出来的对象**（readCache 每次都 new 一个）。
     *
     * <p>userId 为空（理论上不会，日历要求登录）= 不做标记，前端按“其他”处理。
     */
    private RoomCalendarVO decorateInvited(RoomCalendarVO vo, Long userId) {
        if (userId == null) {
            return vo;
        }
        List<Long> meetingIds = vo.getDays().stream()
                .flatMap(day -> day.getMeetings().stream())
                .map(CalendarMeetingVO::getMeetingId)
                .toList();
        if (meetingIds.isEmpty()) {
            return vo;
        }
        Set<Long> invitedIds = new HashSet<>(
                meetingParticipantMapper.selectInvitedMeetingIds(userId, meetingIds));
        vo.getDays().forEach(day -> day.getMeetings()
                .forEach(meeting -> meeting.setInvited(invitedIds.contains(meeting.getMeetingId()))));
        return vo;
    }

    /** TTL = 5min ± 60s：让同一批写入的 key 不会在同一秒集体过期（防雪崩） */
    private Duration jitteredTtl() {
        int offset = ThreadLocalRandom.current().nextInt(-CALENDAR_TTL_JITTER_SECONDS,
                CALENDAR_TTL_JITTER_SECONDS + 1);
        return CALENDAR_TTL.plusSeconds(offset);
    }

    private RoomCalendarVO readCache(String key) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? null
                    : objectMapper.readValue(json, new TypeReference<RoomCalendarVO>() {
                    });
        } catch (Exception e) {
            // 反序列化失败（比如 DTO 改过字段）当 miss 处理：**删掉坏 key**，别让它一直挡住回源
            log.warn("[缓存] 读取失败，按未命中处理并删除: key={}, err={}", key, e.getMessage());
            try {
                redis.delete(key);
            } catch (Exception ignored) {
                // Redis 不可用时连删除都失败，忽略——回源照常
            }
            return null;
        }
    }

    private RoomCalendarVO loadFromDb(Long roomId, LocalDate weekStart) {
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
        vo.setWeekEnd(weekStart.plusDays(6));
        vo.setDays(days);
        return vo;
    }

    // ---------- 事件驱动失效 ----------

    /**
     * 会议变更后删除受影响周的日历缓存（Cache-Aside：先更库、后删缓存）。
     *
     * <p>为什么挂在**领域事件**上而不是逐个塞进写方法里：写路径有创建/改期/取消/审批通过/拒绝/延长/结束
     * 多类，且将来还会加（M5 的周期会议批量、系列取消…）。事件是"业务事实已经发生"的**统一出口**，
     * 新写路径只要发事件就自动获得缓存一致性——漏一个写方法的代价是"用户看到过期日历"，
     * 而且极难察觉。
     *
     * <p>AFTER_COMMIT：事务回滚了就不该删缓存（那时库没变，删了只是白白回源一次）。
     *
     * <p>⚠️ **两类已知缺口**（2026-10-16 评审 R3 补记，此前只记了第二类）：
     * ① **改期**只删"新房间 + 新周"——事件里只有新值，旧房间/旧周的缓存由
     *    {@code MeetingReserveService.update()} 在成功后就地调 {@link CalendarCacheEvictor} 补删
     *    （两处配合，缺一处就会留下一场"已经搬走"的会）；
     * ② **定时任务**把 20→30/40 时不发事件，所以"进行中/已结束"最多滞后一个 TTL（5 分钟）才在日历上体现。
     *    日历是展示用途、TTL 有界，先接受；要即时就让状态任务也发事件。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMeetingChanged(MeetingDomainEvent event) {
        if (event.type() == MeetingEventType.REMIND) {
            return; // 提醒不改变任何日程数据
        }
        cacheEvictor.evict(event.roomId(), event.startTime(), event.endTime());
    }
}
