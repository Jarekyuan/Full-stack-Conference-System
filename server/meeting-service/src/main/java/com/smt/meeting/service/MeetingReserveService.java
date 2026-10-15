package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.dto.MeetingCreateDTO;
import com.smt.meeting.dto.MeetingUpdateDTO;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingParticipant;
import com.smt.meeting.entity.MeetingRoom;
import com.smt.meeting.entity.RoomStrategy;
import com.smt.meeting.enums.MeetingEventEnum;
import com.smt.meeting.enums.MeetingStatusEnum;
import com.smt.meeting.lock.MeetingLockHelper;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingParticipantMapper;
import com.smt.meeting.mapper.MeetingRoomMapper;
import com.smt.meeting.mapper.RoomStrategyMapper;
import com.smt.meeting.statemachine.MeetingStateMachine;
import com.smt.meeting.vo.ExtendLimitVO;
import com.smt.meeting.vo.MeetingConflictVO;
import com.smt.meeting.vo.MeetingCreateVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 预约服务（T2.4 基础版 → T2.5 幂等 → T2.6 分布式锁）
 *
 * 校验链（分两段）：
 *   无锁前置（快速失败，避免无谓抢锁）：会议室可用性 30001/30002 → 时间合法性 10001/30003/30004
 *   锁内复检（并发正确性关键）：冲突检测 40001
 *   分流（R-7）：need_approval → 10，否则 20
 *
 * 并发设计（T2.6）：
 *   锁（lock:meeting:{roomId}:{date}）**包住事务**——用 TransactionTemplate 而非 @Transactional，
 *   否则"锁释放 → 事务提交"之间存在空窗：后一个请求拿到锁时读不到前一请求未提交的数据，
 *   冲突检测会失效（经典陷阱：锁的范围必须 ⊇ 事务的范围）。
 */
@Service
@RequiredArgsConstructor
public class MeetingReserveService {

    private final MeetingMapper meetingMapper;
    private final MeetingRoomMapper meetingRoomMapper;
    private final RoomStrategyMapper roomStrategyMapper;
    private final MeetingParticipantMapper participantMapper;
    private final ConflictChecker conflictChecker;
    private final MeetingStateMachine stateMachine;
    private final MeetingLockHelper lockHelper;
    private final TransactionTemplate transactionTemplate;

    public MeetingCreateVO create(MeetingCreateDTO dto, Long organiserId) {
        // ---------- 第一段：无锁前置校验（快速失败） ----------
        MeetingRoom room = loadAvailableRoom(dto.getRoomId());
        RoomStrategy strategy = roomStrategyMapper.selectByRoomId(room.getId());
        validateTime(dto.getStartTime(), dto.getEndTime(), strategy);

        boolean needApproval = isNeedApproval(strategy);
        int status = stateMachine.initialStatus(needApproval);

        // ---------- 第二段：锁内复检 + 事务落库 ----------
        return lockHelper.executeWithLock(room.getId(), dto.getStartTime(), dto.getEndTime(), () ->
                transactionTemplate.execute(txStatus -> {
                    meetingRoomMapper.selectIdForUpdate(room.getId()); // 数据库兜底（Redis 锁失效仍拦得住）
                    // ★ 冲突检测必须在锁内：锁保证本时段串行，检测结果才可信
                    checkConflict(room.getId(), dto.getStartTime(), dto.getEndTime(), null);

                    Meeting meeting = insertMeeting(dto, room.getId(), organiserId, status);
                    saveParticipants(meeting.getId(), dto.getParticipants());
                    // 状态机补记初始日志（from_status = NULL）
                    stateMachine.logCreation(meeting.getId(), status, organiserId, null);

                    return new MeetingCreateVO(meeting.getId(), status, needApproval,
                            needApproval ? "预约已提交，等待审批" : "预约成功");
                }));
    }

    // ---------- 取消（T2.9） ----------

    /**
     * 取消会议：归属校验（40004，数据权限）→ 取消截止（R-6，30005）→ 状态机迁移（10/20 → 50）→ 写取消信息。
     * 30 进行中禁止取消（迁移矩阵），由状态机拦 40002。
     *
     * 并发设计：取消**也**取房间锁 + 会议室行锁——它同样在改变"某时段是否被占用"（释放时段），
     * 属 CLAUDE.md 红线 #9 的适用范围。不取锁时，取消提交与并发的冲突检测之间会出现窗口：
     * 后来者读到"还没被取消"的旧快照 → 报一场已释放时段的假冲突（评审 Optional）。
     * 锁序与 create/update 一致（先会议室行 → 后会议行），不引入新死锁面。
     */
    public void cancel(Long meetingId, String reason, Long operatorId) {
        Meeting meeting = meetingMapper.selectById(meetingId);
        if (meeting == null) {
            throw new BusinessException(ErrorCode.MEETING_NOT_FOUND);
        }
        // 数据权限：只能操作自己组织的会议
        if (!meeting.getOrganiserId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.NOT_MEETING_OWNER);
        }
        // R-6 取消截止：now > start - cancel_deadline 时不可取消
        RoomStrategy strategy = roomStrategyMapper.selectByRoomId(meeting.getRoomId());
        if (strategy != null && strategy.getCancelDeadlineMinutes() != null) {
            LocalDateTime deadline = meeting.getStartTime().minusMinutes(strategy.getCancelDeadlineMinutes());
            if (LocalDateTime.now().isAfter(deadline)) {
                throw new BusinessException(ErrorCode.CANCEL_DEADLINE_PASSED.getCode(),
                        "距会议开始不足 " + strategy.getCancelDeadlineMinutes() + " 分钟，不可取消");
            }
        }
        lockHelper.executeWithLock(meeting.getRoomId(), meeting.getStartTime(), meeting.getEndTime(), () ->
                transactionTemplate.execute(tx -> {
                    meetingRoomMapper.selectIdForUpdate(meeting.getRoomId()); // 数据库兜底，与 create/update 同一把
                    // 状态变更走状态机唯一入口（校验迁移 + CAS 更新 + 写日志，同一事务）
                    stateMachine.transition(meetingId, MeetingEventEnum.CANCEL, operatorId, reason);
                    // 取消上下文（原因 + 时间）
                    meetingMapper.updateCancelInfo(meetingId, reason);
                    return null;
                }));
    }

    // ---------- 修改（T2.10） ----------

    /**
     * 修改会议：归属校验 → 仅 RESERVED 可改（R-12）→ 改期时重跑校验链+冲突检测（排除自身）
     * → 乐观锁更新（version 校验并发）→ R-14 分流（改到需审批会议室 → 状态回 10）。
     * 只改 title/remark（不改时间/会议室）时不重跑冲突检测。
     *
     * 并发设计（同 create）：**改期必须与创建预约共用同一把房间锁**。
     * 乐观锁只保护"同一行"的并发写，管不住"改到哪"与"谁能订哪"的跨行竞态——
     * 不抢锁的话，改期与新建会各自读到旧快照、各自通过冲突检测，同一时段落下两场会议。
     */
    public void update(Long meetingId, MeetingUpdateDTO dto, Long operatorId) {
        Meeting meeting = meetingMapper.selectById(meetingId);
        if (meeting == null) {
            throw new BusinessException(ErrorCode.MEETING_NOT_FOUND);
        }
        if (!meeting.getOrganiserId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.NOT_MEETING_OWNER);
        }
        // R-12：仅 RESERVED(20) 可修改（延长除外，属 M3）
        if (meeting.getStatus() == null || meeting.getStatus() != MeetingStatusEnum.RESERVED.getCode()) {
            throw new BusinessException(ErrorCode.MEETING_STATUS_NOT_ALLOWED);
        }

        // 是否改了时间/会议室（触发校验链 + R-14 分流）
        boolean scheduleChanged = !dto.getRoomId().equals(meeting.getRoomId())
                || !dto.getStartTime().equals(meeting.getStartTime())
                || !dto.getEndTime().equals(meeting.getEndTime());

        // 只改 title/remark：占用时段没变，不涉及抢时段，无需抢锁
        if (!scheduleChanged) {
            transactionTemplate.executeWithoutResult(tx -> applyUpdate(meetingId, dto, operatorId, false));
            return;
        }

        // 改期：锁内复检 + 事务更新（校验链的无锁前置段同 create，快速失败避免无谓抢锁）
        MeetingRoom room = loadAvailableRoom(dto.getRoomId());
        RoomStrategy strategy = roomStrategyMapper.selectByRoomId(room.getId());
        validateTime(dto.getStartTime(), dto.getEndTime(), strategy);
        boolean backToApproval = isNeedApproval(strategy); // R-14：目标会议室需审批 → 回 10

        lockHelper.executeWithLock(room.getId(), dto.getStartTime(), dto.getEndTime(), () ->
                transactionTemplate.execute(tx -> {
                    meetingRoomMapper.selectIdForUpdate(room.getId()); // 数据库兜底（Redis 锁失效仍拦得住）
                    checkConflict(room.getId(), dto.getStartTime(), dto.getEndTime(), meetingId); // 排除自身
                    applyUpdate(meetingId, dto, operatorId, backToApproval);
                    return null;
                }));
    }

    /** 乐观锁更新 + R-14 状态回退（必须在事务内调用） */
    private void applyUpdate(Long meetingId, MeetingUpdateDTO dto, Long operatorId, boolean backToApproval) {
        // 乐观锁（只改非状态字段 + version 自增）；
        // 同时校 status=RESERVED：改期与取消是两条不同的写路径（后者只改 status、不动 version），
        // 只校 version 拦不住交叉写（评审 C4）。两个条件任一不满足都按 40002 提示刷新重试。
        int affected = meetingMapper.updateWithVersion(meetingId, dto.getTitle(), dto.getRoomId(),
                dto.getStartTime(), dto.getEndTime(), dto.getRemark(), dto.getVersion(),
                MeetingStatusEnum.RESERVED.getCode());
        if (affected == 0) {
            throw new BusinessException(ErrorCode.MEETING_STATUS_NOT_ALLOWED.getCode(),
                    "会议状态已变化（可能已被取消或修改），请刷新后重试");
        }
        // R-14 状态回退：20 → 10（走状态机唯一入口，保证日志一致）
        if (backToApproval) {
            stateMachine.transition(meetingId, MeetingEventEnum.MODIFY, operatorId, "修改到需审批会议室，重新送审");
        }
    }

    // ---------- 延长 / 提前结束（T3.4） ----------

    /**
     * 可延长上限（只读，登录即可查；R-15）：
     * `min(下一场会议开始 − 锁定缓冲, 当日 24:00) − 当前结束时间`，并给出人话解释。
     */
    public ExtendLimitVO extendLimit(Long meetingId) {
        Meeting meeting = meetingMapper.selectById(meetingId);
        if (meeting == null) {
            throw new BusinessException(ErrorCode.MEETING_NOT_FOUND);
        }
        return computeExtendLimit(meeting);
    }

    /**
     * 延长进行中的会议（R-15 + C-10）。
     *
     * 并发设计与 create/update/cancel 同源：**延长会扩大时段占用**（end 往后推），
     * 所以必须走同一把房间锁 + 会议室行锁（CLAUDE.md 红线 #9），否则"延长"与"别人订下一场"
     * 会各自读到旧快照、各自通过校验，落成两场重叠会议。
     *
     * 上限算两遍（锁外一次快速失败、锁内一次作准）：锁外那次是为了让显然超限的请求早点结束，
     * 锁内那次才是裁决——两次之间可能有人插进来一场，这正是 TOCTOU 的经典形态。
     */
    public void extend(Long meetingId, int minutes, Long operatorId) {
        Meeting meeting = meetingMapper.selectById(meetingId);
        if (meeting == null) {
            throw new BusinessException(ErrorCode.MEETING_NOT_FOUND);
        }
        if (!meeting.getOrganiserId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.NOT_MEETING_OWNER);
        }
        requireOngoing(meeting);

        // 锁外快速失败（上限随数据变化，这里只拦"明显超了"的请求）
        long limitOutside = computeExtendLimit(meeting).maxDelayMinutes();
        if (minutes > limitOutside) {
            throw new BusinessException(ErrorCode.EXTEND_LIMIT_EXCEEDED, Map.of("maxDelayMinutes", limitOutside));
        }

        LocalDateTime newEnd = meeting.getEndTime().plusMinutes(minutes);
        lockHelper.executeWithLock(meeting.getRoomId(), meeting.getStartTime(), newEnd, () ->
                transactionTemplate.execute(tx -> {
                    meetingRoomMapper.selectIdForUpdate(meeting.getRoomId());      // ① 会议室行（锁序第一步）
                    Meeting locked = meetingMapper.selectByIdForUpdate(meetingId); // ② 会议行
                    if (locked == null) {
                        throw new BusinessException(ErrorCode.MEETING_NOT_FOUND);
                    }
                    requireOngoing(locked);

                    // 锁内重算上限（作准）：锁外那次算完到这里之间，可能刚有人订了下一场
                    long limitInside = computeExtendLimit(locked).maxDelayMinutes();
                    if (minutes > limitInside) {
                        throw new BusinessException(ErrorCode.EXTEND_LIMIT_EXCEEDED,
                                Map.of("maxDelayMinutes", limitInside));
                    }

                    // 冲突复检（排除自身）：上限已含缓冲，这里是第二道——防"上限是按旧数据算的"
                    List<MeetingConflictVO> conflicts = conflictChecker.findConflicts(
                            locked.getRoomId(), locked.getStartTime(), newEnd, meetingId);
                    if (!conflicts.isEmpty()) {
                        throw new BusinessException(ErrorCode.TIME_CONFLICT, Map.of("conflicts", conflicts));
                    }

                    int affected = meetingMapper.updateEndTime(meetingId, newEnd, locked.getVersion(),
                            MeetingStatusEnum.ONGOING.getCode());
                    if (affected == 0) {
                        throw new BusinessException(ErrorCode.MEETING_STATUS_NOT_ALLOWED.getCode(),
                                "会议状态已变化（可能已结束或被取消），请刷新后重试");
                    }
                    stateMachine.logExtension(meetingId, operatorId, String.format(
                            "延长 %d 分钟（%s → %s）",
                            minutes,
                            locked.getEndTime().format(DateTimeFormatter.ofPattern("HH:mm")),
                            newEnd.format(DateTimeFormatter.ofPattern("HH:mm"))));
                    return null;
                }));
    }

    /**
     * 提前结束进行中的会议（docs/04 3.2）：状态机 30 → 40。
     * 与 cancel 同理要取房间锁——提前结束会**释放**剩余时段，不取锁时并发的冲突检测会读到
     * "还没结束"的旧快照，报一场假冲突。
     */
    public void finish(Long meetingId, Long operatorId) {
        Meeting meeting = meetingMapper.selectById(meetingId);
        if (meeting == null) {
            throw new BusinessException(ErrorCode.MEETING_NOT_FOUND);
        }
        if (!meeting.getOrganiserId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.NOT_MEETING_OWNER);
        }
        // 只允许"进行中"提前结束：迁移表里 RESERVED + END 也通（那是定时任务的"到点结束"），
        // 若不在入口挡住，给未开始的会议点"提前结束"会被静默接受成"已结束"
        requireOngoing(meeting);

        lockHelper.executeWithLock(meeting.getRoomId(), meeting.getStartTime(), meeting.getEndTime(), () ->
                transactionTemplate.execute(tx -> {
                    meetingRoomMapper.selectIdForUpdate(meeting.getRoomId());
                    stateMachine.transition(meetingId, MeetingEventEnum.END, operatorId, "组织人提前结束");
                    return null;
                }));
    }

    /** 延长/提前结束的共同前置：必须是进行中（否则 40002） */
    private void requireOngoing(Meeting meeting) {
        if (meeting.getStatus() == null || meeting.getStatus() != MeetingStatusEnum.ONGOING.getCode()) {
            throw new BusinessException(ErrorCode.MEETING_STATUS_NOT_ALLOWED.getCode(),
                    "仅进行中的会议可执行该操作");
        }
    }

    /** R-15 上限计算：min(下一场开始 − 缓冲, 当日 24:00) − 当前 end，并解释"是谁卡住了你" */
    private ExtendLimitVO computeExtendLimit(Meeting meeting) {
        LocalDateTime end = meeting.getEndTime();
        LocalDateTime dayEnd = end.toLocalDate().plusDays(1).atStartOfDay(); // 当日 24:00
        RoomStrategy strategy = roomStrategyMapper.selectByRoomId(meeting.getRoomId());
        int buffer = strategy == null || strategy.getBufferMinutes() == null ? 0 : strategy.getBufferMinutes();

        LocalDateTime nextStart = meetingMapper.selectNextMeetingStart(meeting.getRoomId(), end, meeting.getId());
        LocalDateTime limit = dayEnd;
        String reason = "当日无后续会议，最多可延至 24:00";
        if (nextStart != null) {
            LocalDateTime byNext = nextStart.minusMinutes(buffer);
            if (byNext.isBefore(limit)) {
                limit = byNext;
                reason = buffer > 0
                        ? String.format("下一场会议 %s 开始（含 %d 分钟锁定缓冲）",
                                nextStart.format(HHMM), buffer)
                        : String.format("下一场会议 %s 开始", nextStart.format(HHMM));
            } else {
                reason = "下一场会议在次日，最多可延至 24:00";
            }
        }
        long maxDelay = Math.max(Duration.between(end, limit).toMinutes(), 0);
        return new ExtendLimitVO(maxDelay, reason);
    }

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    // ---------- 校验链 ----------

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

    /** 时间合法性 + 策略窗口（R-2 时长 / R-5 提前天数）；策略为空时只做基础校验 */
    private void validateTime(LocalDateTime start, LocalDateTime end, RoomStrategy strategy) {
        if (!start.isBefore(end)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "结束时间必须晚于开始时间");
        }
        LocalDateTime now = LocalDateTime.now();
        if (start.isBefore(now)) {
            throw new BusinessException(ErrorCode.OUT_OF_RESERVE_WINDOW.getCode(), "不可预约过去的时间");
        }
        if (strategy != null && strategy.getAdvanceDays() != null
                && start.isAfter(now.plusDays(strategy.getAdvanceDays()))) {
            throw new BusinessException(ErrorCode.OUT_OF_RESERVE_WINDOW.getCode(),
                    "最多提前 " + strategy.getAdvanceDays() + " 天预约");
        }
        if (strategy != null && strategy.getMaxDurationMinutes() != null) {
            long minutes = Duration.between(start, end).toMinutes();
            if (minutes > strategy.getMaxDurationMinutes()) {
                throw new BusinessException(ErrorCode.DURATION_LIMIT_EXCEEDED.getCode(),
                        "超出单场时长上限（上限 " + strategy.getMaxDurationMinutes() + " 分钟）");
            }
        }
    }

    /** 冲突检测：40001 的 data 携带 conflicts 结构化详情（供前端弹冲突详情框，docs/04 4.4） */
    private void checkConflict(Long roomId, LocalDateTime start, LocalDateTime end, Long excludeMeetingId) {
        List<MeetingConflictVO> conflicts = conflictChecker.findConflicts(roomId, start, end, excludeMeetingId);
        if (!conflicts.isEmpty()) {
            throw new BusinessException(ErrorCode.TIME_CONFLICT, Map.of("conflicts", conflicts));
        }
    }

    // ---------- 落库 ----------

    private boolean isNeedApproval(RoomStrategy strategy) {
        return strategy != null && strategy.getNeedApproval() != null && strategy.getNeedApproval() == 1;
    }

    private Meeting insertMeeting(MeetingCreateDTO dto, Long roomId, Long organiserId, int status) {
        Meeting meeting = new Meeting();
        meeting.setTitle(dto.getTitle());
        meeting.setRoomId(roomId);
        meeting.setOrganiserId(organiserId);
        meeting.setStartTime(dto.getStartTime());
        meeting.setEndTime(dto.getEndTime());
        meeting.setStatus(status);
        meeting.setRemark(dto.getRemark());
        meetingMapper.insert(meeting);
        return meeting;
    }

    /** 参会人落库（v1.3：仅系统用户；distinct 去重，联合唯一键兜底防重） */
    private void saveParticipants(Long meetingId, MeetingCreateDTO.Participants participants) {
        if (participants == null || participants.getUserIds() == null || participants.getUserIds().isEmpty()) {
            return;
        }
        List<MeetingParticipant> list = participants.getUserIds().stream()
                .distinct()
                .map(userId -> {
                    MeetingParticipant p = new MeetingParticipant();
                    p.setMeetingId(meetingId);
                    p.setUserId(userId);
                    return p;
                })
                .toList();
        participantMapper.batchInsert(list);
    }
}
