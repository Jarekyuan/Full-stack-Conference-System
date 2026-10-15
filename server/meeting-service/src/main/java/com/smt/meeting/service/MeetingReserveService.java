package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.dto.MeetingCreateDTO;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingParticipant;
import com.smt.meeting.entity.MeetingRoom;
import com.smt.meeting.entity.RoomStrategy;
import com.smt.meeting.lock.MeetingLockHelper;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingParticipantMapper;
import com.smt.meeting.mapper.MeetingRoomMapper;
import com.smt.meeting.mapper.RoomStrategyMapper;
import com.smt.meeting.statemachine.MeetingStateMachine;
import com.smt.meeting.vo.MeetingConflictVO;
import com.smt.meeting.vo.MeetingCreateVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
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
                    // ★ 冲突检测必须在锁内：锁保证本时段串行，检测结果才可信
                    checkConflict(room.getId(), dto.getStartTime(), dto.getEndTime());

                    Meeting meeting = insertMeeting(dto, room.getId(), organiserId, status);
                    saveParticipants(meeting.getId(), dto.getParticipants());
                    // 状态机补记初始日志（from_status = NULL）
                    stateMachine.logCreation(meeting.getId(), status, organiserId, null);

                    return new MeetingCreateVO(meeting.getId(), status, needApproval,
                            needApproval ? "预约已提交，等待审批" : "预约成功");
                }));
    }

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
    private void checkConflict(Long roomId, LocalDateTime start, LocalDateTime end) {
        List<MeetingConflictVO> conflicts = conflictChecker.findConflicts(roomId, start, end, null);
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
