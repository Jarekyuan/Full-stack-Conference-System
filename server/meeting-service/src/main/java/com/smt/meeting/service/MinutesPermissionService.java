package com.smt.meeting.service;

import com.smt.common.context.UserContext;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingParticipantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会议纪要数据权限（Task 8 补完整测试）。
 *
 * <p>管理权限 = 组织人 或 ADMIN；查看权限 = 管理权限 或 参会人。
 * 两个 {@code assert*} 方法只做判定与抛错，不写业务数据。</p>
 */
@Service
@RequiredArgsConstructor
public class MinutesPermissionService {

    private final MeetingMapper meetingMapper;
    private final MeetingParticipantMapper participantMapper;

    public Meeting requireMeeting(Long meetingId) {
        Meeting meeting = meetingMapper.selectById(meetingId);
        if (meeting == null) {
            throw new BusinessException(ErrorCode.MEETING_NOT_FOUND);
        }
        return meeting;
    }

    public boolean canManage(Meeting meeting, Long userId) {
        return canManage(meeting, userId, UserContext.getRoles());
    }

    public boolean canManage(Meeting meeting, Long userId, List<String> roles) {
        return meeting.getOrganiserId().equals(userId)
                || (roles != null && roles.contains("ADMIN"));
    }

    public boolean canView(Meeting meeting, Long userId) {
        return canView(meeting, userId, UserContext.getRoles());
    }

    public boolean canView(Meeting meeting, Long userId, List<String> roles) {
        if (canManage(meeting, userId, roles)) {
            return true;
        }
        return participantMapper.selectByMeetingId(meeting.getId()).stream()
                .anyMatch(participant -> participant.getUserId().equals(userId));
    }

    public void assertCanManage(Meeting meeting, Long userId) {
        if (!canManage(meeting, userId)) {
            throw new BusinessException(ErrorCode.NO_PERMISSION);
        }
    }

    public void assertCanView(Meeting meeting, Long userId) {
        if (!canView(meeting, userId)) {
            throw new BusinessException(ErrorCode.NO_PERMISSION);
        }
    }
}
