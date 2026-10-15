package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.dto.MeetingCreateDTO;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingParticipant;
import com.smt.meeting.entity.MeetingRoom;
import com.smt.meeting.entity.RoomStrategy;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingParticipantMapper;
import com.smt.meeting.mapper.MeetingRoomMapper;
import com.smt.meeting.mapper.RoomStrategyMapper;
import com.smt.meeting.statemachine.MeetingStateMachine;
import com.smt.meeting.vo.MeetingConflictVO;
import com.smt.meeting.vo.MeetingCreateVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 预约服务（T2.4 基础版：校验 → 落库 → 状态机；T2.5 叠幂等、T2.6 叠分布式锁）
 *
 * 校验链（单类方法链，按序执行，失败即抛对应错误码）：
 *   ① 会议室可用性（30001 不存在 / 30002 维护中）
 *   ② 时间合法性（10001 start>=end / 30003 过去时间或超出提前天数 / 30004 超时长上限）
 *   ③ 冲突检测（40001，data 携带 conflicts 结构化详情）
 *   ④ 审批分流（R-7：need_approval → 10，否则 20）
 * 落库在单事务内：meeting + participants + 状态日志（status 由状态机 initialStatus 决定，禁止手写）
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

    @Transactional
    public MeetingCreateVO create(MeetingCreateDTO dto, Long organiserId) {
        MeetingRoom room = loadAvailableRoom(dto.getRoomId());
        RoomStrategy strategy = roomStrategyMapper.selectByRoomId(room.getId());

        validateTime(dto.getStartTime(), dto.getEndTime(), strategy);
        checkConflict(room.getId(), dto.getStartTime(), dto.getEndTime());

        boolean needApproval = isNeedApproval(strategy);
        int status = stateMachine.initialStatus(needApproval);

        Meeting meeting = new Meeting();
        meeting.setTitle(dto.getTitle());
        meeting.setRoomId(room.getId());
        meeting.setOrganiserId(organiserId);
        meeting.setStartTime(dto.getStartTime());
        meeting.setEndTime(dto.getEndTime());
        meeting.setStatus(status);
        meeting.setRemark(dto.getRemark());
        meetingMapper.insert(meeting);

        saveParticipants(meeting.getId(), dto.getParticipants());
        // 状态机补记初始日志（from_status = NULL）
        stateMachine.logCreation(meeting.getId(), status, organiserId, null);

        return new MeetingCreateVO(meeting.getId(), status, needApproval,
                needApproval ? "预约已提交，等待审批" : "预约成功");
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
