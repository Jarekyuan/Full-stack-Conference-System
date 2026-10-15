package com.smt.meeting.statemachine;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingStatusLog;
import com.smt.meeting.enums.MeetingStatusEnum;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingStatusLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.smt.meeting.enums.MeetingEventEnum.APPROVE;
import static com.smt.meeting.enums.MeetingEventEnum.CANCEL;
import static com.smt.meeting.enums.MeetingEventEnum.END;
import static com.smt.meeting.enums.MeetingEventEnum.REJECT;
import static com.smt.meeting.enums.MeetingEventEnum.START;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 状态机单测（依赖本地 MySQL；@Transactional 让测试数据自动回滚，不留痕）
 * 覆盖 docs/05 T2.1 验收：合法迁移全通过 / 非法迁移抛 40002 / 日志按事件落表
 */
@SpringBootTest(properties = "spring.cloud.nacos.discovery.enabled=false")
@Transactional
class MeetingStateMachineTest {

    @Autowired
    private MeetingStateMachine stateMachine;
    @Autowired
    private MeetingMapper meetingMapper;
    @Autowired
    private MeetingStatusLogMapper statusLogMapper;

    /** 造一场指定状态的测试会议，返回 id */
    private Long insertMeeting(int status) {
        Meeting meeting = new Meeting();
        meeting.setTitle("状态机单测-" + status);
        meeting.setRoomId(1L);
        meeting.setOrganiserId(1L);
        meeting.setStartTime(LocalDateTime.of(2026, 11, 1, 10, 0));
        meeting.setEndTime(LocalDateTime.of(2026, 11, 1, 11, 0));
        meeting.setStatus(status);
        meetingMapper.insert(meeting);
        return meeting.getId();
    }

    private int loadStatus(Long id) {
        return meetingMapper.selectById(id).getStatus();
    }

    @Test
    void initialStatusShouldFollowStrategy() {
        assertEquals(MeetingStatusEnum.PENDING_APPROVAL.getCode(), stateMachine.initialStatus(true));
        assertEquals(MeetingStatusEnum.RESERVED.getCode(), stateMachine.initialStatus(false));
    }

    @Test
    void legalTransitionsShouldPass() {
        // 10 待审批的三个出口
        Long a = insertMeeting(10);
        stateMachine.transition(a, APPROVE, 1L);
        assertEquals(20, loadStatus(a));

        Long b = insertMeeting(10);
        stateMachine.transition(b, REJECT, 1L, "时间不合适");
        assertEquals(60, loadStatus(b));

        Long c = insertMeeting(10);
        stateMachine.transition(c, CANCEL, 1L);
        assertEquals(50, loadStatus(c));

        // 20 已预约的三个出口
        Long d = insertMeeting(20);
        stateMachine.transition(d, START, null); // 定时任务：operatorId 为 null
        assertEquals(30, loadStatus(d));

        Long e = insertMeeting(20);
        stateMachine.transition(e, END, null);
        assertEquals(40, loadStatus(e));

        Long f = insertMeeting(20);
        stateMachine.transition(f, CANCEL, 1L);
        assertEquals(50, loadStatus(f));

        // 30 进行中 → 40
        Long g = insertMeeting(30);
        stateMachine.transition(g, END, 1L);
        assertEquals(40, loadStatus(g));
    }

    @Test
    void illegalTransitionsShouldThrow40002() {
        // 30 进行中禁止取消（迁移矩阵明确标注"禁止"）
        Long ongoing = insertMeeting(30);
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> stateMachine.transition(ongoing, CANCEL, 1L));
        assertEquals(ErrorCode.MEETING_STATUS_NOT_ALLOWED.getCode(), e1.getCode());
        assertEquals(30, loadStatus(ongoing), "非法迁移后状态必须不变");

        // 40 已结束是终态，任何事件都不可再迁移
        Long finished = insertMeeting(40);
        assertThrows(BusinessException.class, () -> stateMachine.transition(finished, CANCEL, 1L));
        assertThrows(BusinessException.class, () -> stateMachine.transition(finished, START, null));

        // 20 已预约不能走审批事件（不是待审批状态）
        Long reserved = insertMeeting(20);
        assertThrows(BusinessException.class, () -> stateMachine.transition(reserved, APPROVE, 1L));
    }

    @Test
    void transitionShouldWriteStatusLog() {
        Long id = insertMeeting(10);
        stateMachine.transition(id, APPROVE, 7L, "同意");

        List<MeetingStatusLog> logs = statusLogMapper.selectByMeetingId(id);
        assertEquals(1, logs.size());
        MeetingStatusLog log = logs.get(0);
        assertEquals(10, log.getFromStatus());
        assertEquals(20, log.getToStatus());
        assertEquals("APPROVE", log.getEvent());
        assertEquals(7L, log.getOperatorId());
        assertEquals("同意", log.getRemark());
    }

    @Test
    void logCreationShouldRecordNullFromStatus() {
        Long id = insertMeeting(20);
        stateMachine.logCreation(id, 20, 4L, "免审会议室直接预约");

        List<MeetingStatusLog> logs = statusLogMapper.selectByMeetingId(id);
        assertEquals(1, logs.size());
        assertNull(logs.get(0).getFromStatus(), "创建日志 from_status 应为 NULL");
        assertEquals(20, logs.get(0).getToStatus());
        assertEquals("RESERVE", logs.get(0).getEvent());
        assertEquals(4L, logs.get(0).getOperatorId());
    }
}
