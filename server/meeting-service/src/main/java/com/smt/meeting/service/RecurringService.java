package com.smt.meeting.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.aop.Log;
import com.smt.meeting.dto.RecurringCreateDTO;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingRecurring;
import com.smt.meeting.entity.MeetingRoom;
import com.smt.meeting.entity.RoomStrategy;
import com.smt.meeting.enums.MeetingEventEnum;
import com.smt.meeting.lock.MeetingLockHelper;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingRecurringMapper;
import com.smt.meeting.mapper.MeetingRoomMapper;
import com.smt.meeting.mapper.RoomStrategyMapper;
import com.smt.common.mq.MeetingDomainEvent;
import com.smt.meeting.statemachine.MeetingStateMachine;
import com.smt.meeting.vo.MeetingConflictVO;
import com.smt.meeting.vo.RecurringConflictVO;
import com.smt.meeting.vo.RecurringCancelVO;
import com.smt.meeting.vo.RecurringCreateVO;

/**
 * 周期会议服务（T5.1 创建 / T5.2 系列取消；01 文档流程 5，契约 docs/04 4.9）。
 *
 * <h3>核心设计：系列 = "一批共享同一规则的普通会议"</h3>
 * 不存在"周期会议"这种新状态或新类型——展开出来的每一场就是 meeting 表里普通的一行（带 recurring_id）。
 * 于是状态机、冲突检测、改期/取消、审批、提醒**全都不用为周期会议写第二遍**，
 * 唯一的新东西是"批量创建时的原子性"和"系列级的批量取消"。
 *
 * <h3>批量创建的三个约束（顺序不能变）</h3>
 * ① **逐场校验**（时长/提前量/是否需要审批）——用的是与单场完全相同的校验链（见 MeetingReserveService.validateTime 的说明）；
 * ② **锁住所有场次涉及的自然日 → 再开事务**（锁 ⊇ 事务，红线 #8）：锁的 key 与单场预约同一套，
 *    所以"系列创建"与"并发的单场预约"在相同日期上互斥，不会出现"系列检测通过、单场同时插进来"；
 * ③ **一个事务批量落库**（规则 + N 场 + N 条状态日志）：要么全成、要么全不成。
 *    半截的系列比"整批失败"更难收拾——用户看到失败，库里却已经多了三场会。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecurringService {

    /**
     * 一次最多展开多少场（防御性上限）。
     * 正常情况下提前量（advance_days ≤ 30）已经把场次限制在个位数，
     * 但"策略被配成 365 天"时 52 场 × 每场 3 次 DB 往返会让一次请求拖很久——宁可拒绝，也别让接口变成慢查询。
     */
    private static final int MAX_OCCURRENCES = 60;

    private final MeetingMapper meetingMapper;
    private final MeetingRecurringMapper recurringMapper;
    private final MeetingRoomMapper meetingRoomMapper;
    private final RoomStrategyMapper roomStrategyMapper;
    private final ConflictChecker conflictChecker;
    private final MeetingStateMachine stateMachine;
    private final MeetingReserveService reserveService;
    private final MeetingLockHelper lockHelper;
    private final TransactionTemplate transactionTemplate;
    private final CalendarCacheEvictor calendarCacheEvictor;
    /** 逐场取消要发领域事件（与单场取消一致：参会人该知道这会没了） */
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /**
     * 创建周期会议。
     *
     * @throws BusinessException 30001 会议室不存在 / 30002 维护中 / 40007 未开放周期预约 /
     *                           40005 任一场次冲突（data.conflicts 带场次日期）/ 10001 参数与校验链不符
     */
    @Log(type = "RECURRING_CREATE", targetFromResultOnly = true)
    public RecurringCreateVO create(RecurringCreateDTO dto, Long organiserId) {
        // ---------- 第一段：无锁前置校验（快速失败） ----------
        MeetingRoom room = loadAvailableRoom(dto.getRoomId());
        RoomStrategy strategy = roomStrategyMapper.selectByRoomId(room.getId());
        if (strategy != null && Integer.valueOf(0).equals(strategy.getAllowRecurring())) {
            throw new BusinessException(ErrorCode.RECURRING_NOT_ALLOWED);
        }
        List<LocalDateTime[]> occurrences = expand(dto, strategy, room.getId());

        // ---------- 第二段：锁住所有场次日期 → 事务内逐场复检 + 批量落库 ----------
        SortedSet<LocalDate> dates = occurrences.stream()
                .map(occ -> occ[0].toLocalDate())
                .collect(Collectors.toCollection(TreeSet::new));
        return lockHelper.executeWithLockOnDates(room.getId(), dates, () ->
                transactionTemplate.execute(tx -> {
                    meetingRoomMapper.selectIdForUpdate(room.getId()); // 数据库兜底，与单场创建同一把

                    List<RecurringConflictVO> conflicts = checkConflicts(room.getId(), occurrences);
                    if (!conflicts.isEmpty()) {
                        throw new BusinessException(ErrorCode.RECURRING_CONFLICT, Map.of("conflicts", conflicts));
                    }

                    boolean needApproval = isNeedApproval(strategy);
                    int status = stateMachine.initialStatus(needApproval);

                    MeetingRecurring rule = new MeetingRecurring();
                    rule.setOrganiserId(organiserId);
                    rule.setRoomId(room.getId());
                    rule.setTitle(dto.getTitle());
                    rule.setDayOfWeek(dto.getDayOfWeek());
                    rule.setStartMinutes(dto.getStartMinutes());
                    rule.setDurationMinutes(dto.getDurationMinutes());
                    rule.setStartDate(dto.getStartDate());
                    rule.setEndDate(dto.getEndDate());
                    recurringMapper.insert(rule);

                    List<Meeting> meetings = occurrences.stream().map(occ -> {
                        Meeting m = new Meeting();
                        m.setTitle(dto.getTitle());
                        m.setRoomId(room.getId());
                        m.setOrganiserId(organiserId);
                        m.setStartTime(occ[0]);
                        m.setEndTime(occ[1]);
                        m.setStatus(status);
                        m.setRecurringId(rule.getId());
                        return m;
                    }).toList();
                    meetingMapper.insertBatch(meetings);
                    // 每场补一条创建日志（红线 #4：状态变更必须留痕；时间线里"这一场是怎么来的"要说得清）
                    for (Meeting m : meetings) {
                        stateMachine.logCreation(m.getId(), status, organiserId, "周期会议");
                    }

                    // 日历缓存：直接删覆盖区间（**不逐场发领域事件**——那会顺带产生 N 条"预约创建"通知，
                    // 组织人自己刚建的系列没必要收 5 条；缓存失效是本进程内的事，直接调用更直白）
                    calendarCacheEvictor.evict(room.getId(), occurrences.get(0)[0],
                            occurrences.get(occurrences.size() - 1)[1]);

                    log.info("[周期会议] 已创建系列 {}：{} 场（room={}, {} 起每周{} {}）",
                            rule.getId(), meetings.size(), room.getId(), dto.getStartDate(),
                            dto.getDayOfWeek(), LocalTime.of(dto.getStartMinutes() / 60, dto.getStartMinutes() % 60));
                    return new RecurringCreateVO(rule.getId(), meetings.size(),
                            meetings.stream().map(Meeting::getId).toList());
                }));
    }

    /**
     * 取消整个系列（T5.2，契约 docs/04 4.9）：只取消"未开始"的场次（10 待审批 / 20 已预约）。
     *
     * <h3>三条规则</h3>
     * ① **历史场次不受影响**：已结束（40）、已取消（50）、已拒绝（60）本来就没有出边（R-11），
     *    这里直接跳过并计入 skipped —— 不是错误，是"系列里有过去的会"这件正常的事；
     * ② ⚠️ **系列里有"进行中"（30）的场次 → 整个系列取消被拒**：把正在开的会"取消"在业务上说不通
     *    （它不是"没发生"，是"正在发生"），提示先单场处理（单场也取消不了 30，需要的是提前结束）；
     * ③ **逐场走状态机**（红线 #4）：每场一条 meeting_status_log，详情页时间线里说得清"这场是怎么没的"。
     *
     * <h3>并发</h3>
     * 与单场取消同一套：**锁住所有被取消场次涉及的自然日 → 再开事务**（锁 ⊇ 事务），
     * 事务内先锁会议室行。取消会**释放时段**，不取同一把锁的话，并发的冲突检测会读到"还没取消"的旧快照，
     * 报一场假冲突（与 T2.9 单场取消同理）。
     *
     * @throws BusinessException 40008 系列不存在 / 40004 非组织人 / 40002 系列内有进行中的场次
     */
    @Log(type = "RECURRING_CANCEL")
    public RecurringCancelVO cancel(Long recurringId, String reason, Long operatorId) {
        MeetingRecurring rule = recurringMapper.selectById(recurringId);
        if (rule == null) {
            throw new BusinessException(ErrorCode.RECURRING_NOT_FOUND);
        }
        if (!rule.getOrganiserId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.NOT_MEETING_OWNER);
        }
        List<Meeting> meetings = meetingMapper.selectByRecurringId(recurringId);

        // 未开始 = 还能取消的（10/20）；其余是历史，直接算 skipped
        List<Meeting> cancellable = meetings.stream()
                .filter(m -> m.getStatus() != null && (m.getStatus() == 10 || m.getStatus() == 20))
                .toList();
        List<Meeting> ongoing = meetings.stream()
                .filter(m -> m.getStatus() != null && m.getStatus() == 30)
                .toList();
        if (!ongoing.isEmpty()) {
            throw new BusinessException(ErrorCode.MEETING_STATUS_NOT_ALLOWED.getCode(),
                    String.format("系列内有 %d 场进行中的会议（最早 %s），请先单独处理",
                            ongoing.size(), ongoing.get(0).getStartTime()));
        }
        if (cancellable.isEmpty()) {
            // 没有可取消的场次：不算错误（可能全是历史场次），返回 0/总数，让调用方自己判断
            return new RecurringCancelVO(0, meetings.size());
        }

        SortedSet<LocalDate> dates = cancellable.stream()
                .map(m -> m.getStartTime().toLocalDate())
                .collect(Collectors.toCollection(TreeSet::new));
        return lockHelper.executeWithLockOnDates(rule.getRoomId(), dates, () ->
                transactionTemplate.execute(tx -> {
                    meetingRoomMapper.selectIdForUpdate(rule.getRoomId()); // 数据库兜底，与单场取消同一把
                    for (Meeting m : cancellable) {
                        stateMachine.transition(m.getId(), MeetingEventEnum.CANCEL, operatorId, reason);
                        meetingMapper.updateCancelInfo(m.getId(), reason);
                        // 领域事件：**逐场发**（单场取消也是这么做的）。系列场次通常没有参会人 →
                        // 实际不会产生通知；万一后来给某几场加了参会人，他们本来就该被告知
                        eventPublisher.publishEvent(MeetingDomainEvent.cancelled(
                                m.getId(), m.getTitle(), m.getRoomId(), m.getOrganiserId(),
                                m.getStartTime(), m.getEndTime(), reason));
                    }
                    // 日历缓存：直接删覆盖区间（与创建同理：不逐场发事件去驱动缓存，本进程内直接调用更直白）
                    calendarCacheEvictor.evict(rule.getRoomId(),
                            cancellable.get(0).getStartTime(),
                            cancellable.get(cancellable.size() - 1).getEndTime());

                    log.info("[周期会议] 系列 {} 已取消 {} 场（历史场次跳过 {} 场）",
                            recurringId, cancellable.size(), meetings.size() - cancellable.size());
                    return new RecurringCancelVO(cancellable.size(), meetings.size() - cancellable.size());
                }));
    }

    // ---------- 内部 ----------

    /**
     * 展开场次：只取落在 [startDate, endDate] 内、星期匹配的日期，逐场过**同一条校验链**。
     *
     * <p>逐场校验（而不是"只看第一场"）：提前量、时长上限都可能让**后面的场次**越界
     * （例如 endDate 定在 40 天后，而会议室只能提前 30 天预约）——只看第一场会放过去，
     * 到落库时才炸，或者更糟：悄悄建出越界的会议。
     */
    private List<LocalDateTime[]> expand(RecurringCreateDTO dto, RoomStrategy strategy, Long roomId) {
        if (dto.getStartDate().isAfter(dto.getEndDate())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "重复结束日期不能早于开始日期");
        }
        LocalTime startTime = minutesToTime(dto.getStartMinutes());
        LocalTime endTime = startTime.plusMinutes(dto.getDurationMinutes());
        if (endTime.isBefore(startTime) || endTime.equals(LocalTime.MIDNIGHT)) {
            // 跨天（09:00 + 720 分钟落到次日）：本系统的占用模型是"按自然日加锁"，跨天场次会让锁与占用对不上
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "单场时长不能跨天（结束时间须在当日 24:00 前）");
        }

        List<LocalDateTime[]> occurrences = new ArrayList<>();
        for (LocalDate d = dto.getStartDate(); !d.isAfter(dto.getEndDate()); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.of(dto.getDayOfWeek())) {
                continue;
            }
            LocalDateTime start = LocalDateTime.of(d, startTime);
            LocalDateTime end = LocalDateTime.of(d, endTime);
            try {
                reserveService.validateTime(start, end, strategy);
            } catch (BusinessException e) {
                // 把"哪一场不合适"直接说清楚：只报"参数错误"用户得自己一个个日期去猜
                throw new BusinessException(e.getCode(),
                        String.format("第 %d 场（%s）不合适：%s", occurrences.size() + 1, d, e.getMessage()));
            }
            occurrences.add(new LocalDateTime[]{start, end});
            if (occurrences.size() > MAX_OCCURRENCES) {
                throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(),
                        "一次最多创建 " + MAX_OCCURRENCES + " 场，请缩短重复区间");
            }
        }
        if (occurrences.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(),
                    "所选区间内没有匹配的日期（检查重复星期与起止日期）");
        }
        return occurrences;
    }

    /** 逐场冲突检测（锁内调用）：把冲突归属到具体场次上 */
    private List<RecurringConflictVO> checkConflicts(Long roomId, List<LocalDateTime[]> occurrences) {
        List<RecurringConflictVO> conflicts = new ArrayList<>();
        for (LocalDateTime[] occ : occurrences) {
            for (MeetingConflictVO c : conflictChecker.findConflicts(roomId, occ[0], occ[1], null)) {
                RecurringConflictVO vo = new RecurringConflictVO();
                vo.setOccurrenceStart(occ[0]);
                vo.setMeetingId(c.getMeetingId());
                vo.setTitle(c.getTitle());
                vo.setStartTime(c.getStartTime());
                vo.setEndTime(c.getEndTime());
                vo.setOrganiserId(c.getOrganiserId());
                vo.setOrganiserName(c.getOrganiserName());
                conflicts.add(vo);
            }
        }
        return conflicts;
    }

    private MeetingRoom loadAvailableRoom(Long roomId) {
        MeetingRoom room = meetingRoomMapper.selectById(roomId);
        if (room == null) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }
        if (room.getStatus() != null && room.getStatus() == 1) {
            throw new BusinessException(ErrorCode.ROOM_MAINTENANCE);
        }
        return room;
    }

    /** 策略是否需要审批（与单场创建同一判定：策略缺失按"不需审批"处理） */
    private boolean isNeedApproval(RoomStrategy strategy) {
        return strategy != null && Integer.valueOf(1).equals(strategy.getNeedApproval());
    }

    /** 当天分钟数 → LocalTime（540 → 09:00），非法值在 DTO 层已挡（0~1439） */
    private LocalTime minutesToTime(Integer minutes) {
        return LocalTime.of(minutes / 60, minutes % 60);
    }
}
