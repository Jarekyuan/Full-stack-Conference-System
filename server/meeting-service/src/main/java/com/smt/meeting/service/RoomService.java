package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.mapper.MeetingRoomMapper;
import com.smt.meeting.mapper.RoomGroupMapper;
import com.smt.meeting.vo.RoomGroupVO;
import com.smt.meeting.vo.RoomVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会议室查询服务（T1.15）：分组 / 列表（筛选）/ 详情
 */
@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomGroupMapper roomGroupMapper;
    private final MeetingRoomMapper meetingRoomMapper;

    public List<RoomGroupVO> listGroups() {
        return roomGroupMapper.selectAll().stream()
                .map(group -> new RoomGroupVO(group.getId(), group.getName(), group.getSortOrder()))
                .toList();
    }

    /** 列表：capacity 为"至少可容纳 N 人"语义（>=） */
    public List<RoomVO> list(Long groupId, Integer capacity, Integer status) {
        return meetingRoomMapper.selectList(groupId, capacity, status);
    }

    public RoomVO detail(Long id) {
        RoomVO room = meetingRoomMapper.selectDetailById(id);
        if (room == null) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }
        return room;
    }
}
