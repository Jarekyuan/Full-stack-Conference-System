package com.smt.meeting.service;

import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingParticipant;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingParticipantMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 纪要数据权限：管理 = 组织人 / ADMIN；查看 = 管理 / 参会人。
 * 显式传入 roles，避免测试依赖 UserContext（ThreadLocal）。
 */
class MinutesPermissionServiceTest {

    private final MeetingMapper meetingMapper = mock(MeetingMapper.class);
    private final MeetingParticipantMapper participantMapper = mock(MeetingParticipantMapper.class);
    private final MinutesPermissionService service =
            new MinutesPermissionService(meetingMapper, participantMapper);

    private Meeting meeting() {
        Meeting meeting = new Meeting();
        meeting.setId(42L);
        meeting.setOrganiserId(4L);
        return meeting;
    }

    @Test
    void organiserCanManageAndView() {
        Meeting meeting = meeting();
        assertThat(service.canManage(meeting, 4L, List.of())).isTrue();
        assertThat(service.canView(meeting, 4L, List.of())).isTrue();
    }

    @Test
    void adminCanManageAndView() {
        Meeting meeting = meeting();
        assertThat(service.canManage(meeting, 9L, List.of("ADMIN"))).isTrue();
        assertThat(service.canView(meeting, 9L, List.of("ADMIN"))).isTrue();
    }

    @Test
    void participantCanViewButNotManage() {
        Meeting meeting = meeting();
        MeetingParticipant participant = new MeetingParticipant();
        participant.setMeetingId(42L);
        participant.setUserId(7L);
        when(participantMapper.selectByMeetingId(42L)).thenReturn(List.of(participant));

        assertThat(service.canView(meeting, 7L, List.of("USER"))).isTrue();
        assertThat(service.canManage(meeting, 7L, List.of("USER"))).isFalse();
    }

    @Test
    void strangerCannotView() {
        Meeting meeting = meeting();
        when(participantMapper.selectByMeetingId(42L)).thenReturn(List.of());

        assertThat(service.canView(meeting, 8L, List.of("USER"))).isFalse();
        assertThat(service.canManage(meeting, 8L, List.of("USER"))).isFalse();
    }
}
