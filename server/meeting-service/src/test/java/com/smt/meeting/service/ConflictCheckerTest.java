package com.smt.meeting.service;

import com.smt.meeting.entity.Meeting;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.vo.MeetingConflictVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冲突检测单测（依赖本地 MySQL；@Transactional 自动回滚）
 * 覆盖 docs/05 T2.2 验收：相邻不冲突 / 包含·交叉·相同全冲突 / buffer 生效
 * 另补：R-4 待审批不占用、修改场景排除自身
 *
 * 房间选择：roomId=1 青竹厅（策略 buffer=0，测基础判重）；roomId=3 梧桐厅（buffer=15，测缓冲）
 */
@SpringBootTest(properties = "spring.cloud.nacos.discovery.enabled=false")
@Transactional
class ConflictCheckerTest {

    @Autowired
    private ConflictChecker conflictChecker;
    @Autowired
    private MeetingMapper meetingMapper;

    private static final LocalDateTime T10 = LocalDateTime.of(2026, 11, 10, 10, 0);
    private static final LocalDateTime T11 = LocalDateTime.of(2026, 11, 10, 11, 0);
    private static final LocalDateTime T12 = LocalDateTime.of(2026, 11, 10, 12, 0);

    private Long insertMeeting(Long roomId, LocalDateTime start, LocalDateTime end, int status) {
        Meeting meeting = new Meeting();
        meeting.setTitle("冲突检测单测");
        meeting.setRoomId(roomId);
        meeting.setOrganiserId(1L);
        meeting.setStartTime(start);
        meeting.setEndTime(end);
        meeting.setStatus(status);
        meetingMapper.insert(meeting);
        return meeting.getId();
    }

    @Test
    void adjacentShouldNotConflict() {
        insertMeeting(1L, T10, T11, 20);

        // 后相邻：11:00 开始（半开区间：11:00 > 11:00 为假 → 不冲突）
        assertTrue(conflictChecker.findConflicts(1L, T11, T12, null).isEmpty(), "11:00 开始应与 10-11 场次相邻不冲突");
        // 前相邻：09:00 - 10:00
        assertTrue(conflictChecker.findConflicts(1L, T10.minusHours(1), T10, null).isEmpty(), "10:00 结束应与 10-11 场次相邻不冲突");
    }

    @Test
    void overlapCasesShouldConflict() {
        insertMeeting(1L, T10, T11, 20);

        // 包含（新会议在已有会议内部）
        assertEquals(1, conflictChecker.findConflicts(1L, T10.plusMinutes(30), T10.plusMinutes(45), null).size());
        // 交叉（尾段重叠）
        assertEquals(1, conflictChecker.findConflicts(1L, T10.plusMinutes(30), T11.plusMinutes(30), null).size());
        // 完全相同
        assertEquals(1, conflictChecker.findConflicts(1L, T10, T11, null).size());
        // 被包含（已有会议在新会议内部）
        assertEquals(1, conflictChecker.findConflicts(1L, T10.minusMinutes(30), T11.plusMinutes(30), null).size());
    }

    @Test
    void bufferShouldExtendDetectionRange() {
        // 梧桐厅（roomId=3）buffer=15：已有会议 10:00-11:00 → 实际占用 [09:45, 11:15)
        insertMeeting(3L, T10, T11, 20);

        // 09:50-10:00 落在缓冲区内 → 冲突
        assertEquals(1, conflictChecker.findConflicts(3L, T10.minusMinutes(10), T10, null).size(),
                "09:50 开始应落入 15 分钟缓冲 → 冲突");
        // 11:10-11:20 落在结束缓冲内 → 冲突
        assertEquals(1, conflictChecker.findConflicts(3L, T11.plusMinutes(10), T11.plusMinutes(20), null).size(),
                "11:10 开始应落入结束缓冲 → 冲突");
        // 09:30-09:45：恰好贴住缓冲边界（半开区间）→ 不冲突
        assertTrue(conflictChecker.findConflicts(3L, T10.minusMinutes(30), T10.minusMinutes(15), null).isEmpty(),
                "09:45 结束应贴边界不冲突");
        // 对照：同样的时间放青竹厅（buffer=0）→ 不冲突
        assertTrue(conflictChecker.findConflicts(1L, T10.minusMinutes(10), T10, null).isEmpty(),
                "buffer=0 的房间不应有缓冲冲突");
    }

    @Test
    void pendingApprovalShouldNotOccupy() {
        // R-4：待审批（10）不占用时段
        insertMeeting(1L, T10, T11, 10);
        assertTrue(conflictChecker.findConflicts(1L, T10, T11, null).isEmpty(), "10 待审批不应占用时段");

        // 已取消（50）同样不占用
        insertMeeting(1L, T10, T11, 50);
        assertTrue(conflictChecker.findConflicts(1L, T10, T11, null).isEmpty(), "50 已取消不应占用时段");
    }

    @Test
    void excludeMeetingIdShouldSkipSelf() {
        Long id = insertMeeting(1L, T10, T11, 20);

        // 排除自身：修改会议场景（时间不变不应与自己冲突）
        assertTrue(conflictChecker.findConflicts(1L, T10, T11, id).isEmpty(), "排除自身后不应冲突");
        // 不排除时冲突
        assertFalse(conflictChecker.findConflicts(1L, T10, T11, null).isEmpty());
    }

    @Test
    void conflictDetailShouldCarryMeetingInfo() {
        Long id = insertMeeting(1L, T10, T11, 20);

        List<MeetingConflictVO> conflicts = conflictChecker.findConflicts(1L, T10, T11, null);
        assertEquals(1, conflicts.size());
        MeetingConflictVO vo = conflicts.get(0);
        assertEquals(id, vo.getMeetingId());
        assertEquals("冲突检测单测", vo.getTitle());
        assertEquals(T10, vo.getStartTime());
        assertEquals(T11, vo.getEndTime());
        assertEquals("系统管理员", vo.getOrganiserName(), "应 JOIN 出组织人姓名（organiserId=1 为 admin）");
    }
}
