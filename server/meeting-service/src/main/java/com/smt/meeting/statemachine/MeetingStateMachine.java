package com.smt.meeting.statemachine;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingStatusLog;
import com.smt.meeting.enums.MeetingEventEnum;
import com.smt.meeting.enums.MeetingStatusEnum;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingStatusLogMapper;

import lombok.RequiredArgsConstructor;

/**
 * 会议状态机：**所有状态变更的唯一入口**（对照 docs/01 4.5 迁移矩阵）
 *
 * 设计要点：
 * ① 迁移表驱动：Map<当前状态, Map<事件, 目标状态>>，未列出的组合一律视为非法（fail-closed）；
 * ② 校验 → CAS 更新 → 写日志三步在同一事务内（@Transactional），任何一步失败整体回滚；
 * ③ 并发防线用 CAS（UPDATE ... WHERE status = 前态）：影响 0 行说明状态已被并发变更，按 40002 处理——
 * 与"校验时读到的状态"相比，CAS 才是真正防住并发的那一步；
 * ④ 禁止业务代码直接 setStatus：绕过状态机 = 绕过校验与日志。
 */
@Component
@RequiredArgsConstructor
public class MeetingStateMachine {

    private final MeetingMapper meetingMapper;
    private final MeetingStatusLogMapper statusLogMapper;

    /** 迁移表（严格对照 docs/01 4.5 矩阵；"（无）"行的创建场景见 {@link #initialStatus}） */
    private static final Map<Integer, Map<MeetingEventEnum, Integer>> TRANSITIONS = Map.of(
            MeetingStatusEnum.PENDING_APPROVAL.getCode(), Map.of(
                    MeetingEventEnum.APPROVE, MeetingStatusEnum.RESERVED.getCode(), // 10 → 20
                    MeetingEventEnum.REJECT, MeetingStatusEnum.REJECTED.getCode(), // 10 → 60
                    MeetingEventEnum.CANCEL, MeetingStatusEnum.CANCELLED.getCode() // 10 → 50
            ),
            MeetingStatusEnum.RESERVED.getCode(), Map.of(
                    MeetingEventEnum.START, MeetingStatusEnum.ONGOING.getCode(), // 20 → 30
                    MeetingEventEnum.END, MeetingStatusEnum.FINISHED.getCode(), // 20 → 40
                    MeetingEventEnum.CANCEL, MeetingStatusEnum.CANCELLED.getCode(), // 20 → 50
                    MeetingEventEnum.MODIFY, MeetingStatusEnum.PENDING_APPROVAL.getCode() // 20 → 10（R-14：改到需审批会议室）
            ),
            MeetingStatusEnum.ONGOING.getCode(), Map.of(
                    MeetingEventEnum.END, MeetingStatusEnum.FINISHED.getCode() // 30 → 40（含提前结束）
            // 注意：30 禁止 CANCEL（矩阵标注），故此处不登记 CANCEL
            ));

    /**
     * 创建时的初始状态（矩阵"（无）"行）：需审批 → 10 待审批；免审 → 20 已预约。
     * 由调用方在 insert 前决定落库状态，再用 {@link #logCreation} 补记初始日志。
     */
    public int initialStatus(boolean needApproval) {
        return needApproval
                ? MeetingStatusEnum.PENDING_APPROVAL.getCode()
                : MeetingStatusEnum.RESERVED.getCode();
    }

    /** 记录创建日志（from_status = NULL），在创建事务内调用 */
    public void logCreation(Long meetingId, int toStatus, Long operatorId, String remark) {
        insertLog(meetingId, null, toStatus, MeetingEventEnum.RESERVE, operatorId, remark);
    }

    /**
     * 记录"延长会议"审计日志（T3.4）：状态不变（30 → 30），变的是 `end_time`。
     *
     * 为什么走状态机而不是业务代码直接插日志：延长的每一次 `end_time` 变化都必须留下
     * who/when/为什么，审计口径要与其他变更一致（时间线里能看出"14:00-15:00 延长成 14:00-16:00"）。
     * 不能用 {@link #transition}：它要求状态真的发生变化，CAS 会以 0 行失败收场。
     */
    public void logExtension(Long meetingId, Long operatorId, String remark) {
        insertLog(meetingId, MeetingStatusEnum.ONGOING.getCode(), MeetingStatusEnum.ONGOING.getCode(),
                MeetingEventEnum.EXTEND, operatorId, remark);
    }

    /** 状态迁移（无备注的便捷重载；事务由 4 参方法所在的 public 入口保证） */
    @Transactional
    public void transition(Long meetingId, MeetingEventEnum event, Long operatorId) {
        transition(meetingId, event, operatorId, null);
    }

    /**
     * 状态迁移唯一入口。
     * 
     * @throws BusinessException 40003 会议不存在 / 40002 当前状态不允许该事件（含并发导致的 CAS 失败）
     */
    @Transactional
    public void transition(Long meetingId, MeetingEventEnum event, Long operatorId, String remark) {
        Meeting meeting = meetingMapper.selectById(meetingId);
        if (meeting == null) {
            throw new BusinessException(ErrorCode.MEETING_NOT_FOUND);
        }

        Integer fromStatus = meeting.getStatus();
        Integer toStatus = TRANSITIONS.getOrDefault(fromStatus, Map.of()).get(event);
        if (toStatus == null) {
            throw new BusinessException(ErrorCode.MEETING_STATUS_NOT_ALLOWED);
        }

        // CAS：并发下只有一个请求能把状态从 fromStatus 改走
        int affected = meetingMapper.updateStatus(meetingId, fromStatus, toStatus);
        if (affected == 0) {
            throw new BusinessException(ErrorCode.MEETING_STATUS_NOT_ALLOWED);
        }

        insertLog(meetingId, fromStatus, toStatus, event, operatorId, remark);
    }

    private void insertLog(Long meetingId, Integer fromStatus, Integer toStatus,
            MeetingEventEnum event, Long operatorId, String remark) {
        MeetingStatusLog log = new MeetingStatusLog();
        log.setMeetingId(meetingId);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setEvent(event.name());
        log.setOperatorId(operatorId);
        log.setRemark(remark);
        statusLogMapper.insert(log);
    }
}
