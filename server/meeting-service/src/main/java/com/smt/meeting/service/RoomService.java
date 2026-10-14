package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.dto.RoomSaveDTO;
import com.smt.meeting.dto.StrategyDTO;
import com.smt.meeting.entity.MeetingRoom;
import com.smt.meeting.entity.RoomStrategy;
import com.smt.meeting.mapper.MeetingRoomMapper;
import com.smt.meeting.mapper.RoomGroupMapper;
import com.smt.meeting.mapper.RoomStrategyMapper;
import com.smt.meeting.vo.RoomGroupVO;
import com.smt.meeting.vo.RoomVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会议室服务（T1.15 查询 + T1.16 管理）
 */
@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomGroupMapper roomGroupMapper;
    private final MeetingRoomMapper meetingRoomMapper;
    private final RoomStrategyMapper roomStrategyMapper;

    // ---------- 查询（T1.15） ----------

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

    // ---------- 管理（T1.16，ADMIN） ----------

    /** 新增会议室 + 自动创建默认策略（同一事务，默认值对齐种子标准厅） */
    @Transactional
    public Long create(RoomSaveDTO dto) {
        MeetingRoom room = new MeetingRoom();
        room.setName(dto.getName());
        room.setGroupId(dto.getGroupId());
        room.setCapacity(dto.getCapacity());
        room.setLocation(dto.getLocation());
        room.setImages(dto.getImages());
        room.setDeviceTags(dto.getDeviceTags());
        room.setStatus(0);
        meetingRoomMapper.insert(room);

        RoomStrategy strategy = new RoomStrategy();
        strategy.setRoomId(room.getId());
        strategy.setNeedApproval(0);
        strategy.setAdvanceDays(30);
        strategy.setMaxDurationMinutes(240);
        strategy.setCancelDeadlineMinutes(30);
        strategy.setBufferMinutes(0);
        strategy.setAllowRecurring(1);
        roomStrategyMapper.insert(strategy);
        return room.getId();
    }

    /** 修改（动态更新：仅提交的字段生效） */
    @Transactional
    public void update(Long id, RoomSaveDTO dto) {
        ensureRoomExists(id);
        MeetingRoom room = new MeetingRoom();
        room.setId(id);
        room.setName(dto.getName());
        room.setGroupId(dto.getGroupId());
        room.setCapacity(dto.getCapacity());
        room.setLocation(dto.getLocation());
        room.setImages(dto.getImages());
        room.setDeviceTags(dto.getDeviceTags());
        room.setStatus(dto.getStatus());
        meetingRoomMapper.update(room);
    }

    /** 删除：校验无未结束会议 + 级联清理策略（无数据库外键，应用层保证） */
    @Transactional
    public void remove(Long id) {
        ensureRoomExists(id);
        if (meetingRoomMapper.countActiveMeetingsByRoom(id) > 0) {
            throw new BusinessException(ErrorCode.ROOM_HAS_ACTIVE_MEETINGS);
        }
        meetingRoomMapper.deleteById(id);
        roomStrategyMapper.deleteByRoomId(id);
    }

    /** 策略全量更新（历史数据缺失策略时兜底创建） */
    @Transactional
    public void updateStrategy(Long id, StrategyDTO dto) {
        ensureRoomExists(id);
        RoomStrategy strategy = new RoomStrategy();
        strategy.setRoomId(id);
        strategy.setNeedApproval(dto.getNeedApproval());
        strategy.setAdvanceDays(dto.getAdvanceDays());
        strategy.setMaxDurationMinutes(dto.getMaxDurationMinutes());
        strategy.setCancelDeadlineMinutes(dto.getCancelDeadlineMinutes());
        strategy.setBufferMinutes(dto.getBufferMinutes());
        strategy.setAllowRecurring(dto.getAllowRecurring());
        if (roomStrategyMapper.updateByRoomId(strategy) == 0) {
            roomStrategyMapper.insert(strategy);
        }
    }

    private void ensureRoomExists(Long id) {
        if (meetingRoomMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }
    }
}
