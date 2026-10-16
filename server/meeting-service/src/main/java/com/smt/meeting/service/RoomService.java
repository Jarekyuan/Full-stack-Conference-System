package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.config.CacheConfig;
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
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会议室服务（T1.15 查询 + T1.16 管理）
 *
 * <h3>缓存（T4.7）</h3>
 * 列表与详情走 {@code @Cacheable}（Redis，30 分钟，见 {@link CacheConfig}）：
 * 会议室是**极低频变更、极高频读取**的数据（每次打开预约表单/日历都要拉列表），是典型的缓存对象。
 *
 * <p>失效策略：**写路径整体清空**（{@code allEntries = true}）而不是逐个精准删 key——
 * 会议室表只有个位数行，而"改一个会议室会影响哪些缓存 key"的答案是"列表的所有筛选组合 + 该条详情"，
 * 精准维护的复杂度远高于它省下的那点回源。**低频写 + 小数据量**是 allEntries 成立的两个前提，
 * 换成会议表（高频写）就必须按 key 精准删。
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

    /** 列表：capacity 为"至少可容纳 N 人"语义（>=）；device 为设备标签精确命中（JSON 数组包含） */
    @Cacheable(cacheNames = CacheConfig.ROOM_LIST_CACHE, key = "#groupId + ':' + #capacity + ':' + #status + ':' + #device")
    public List<RoomVO> list(Long groupId, Integer capacity, Integer status, String device) {
        return meetingRoomMapper.selectList(groupId, capacity, status, device);
    }

    @Cacheable(cacheNames = CacheConfig.ROOM_DETAIL_CACHE, key = "#id")
    public RoomVO detail(Long id) {
        RoomVO room = meetingRoomMapper.selectDetailById(id);
        if (room == null) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }
        return room;
    }

    // ---------- 管理（T1.16，ADMIN） ----------

    /** 新增会议室 + 自动创建默认策略（同一事务，默认值对齐种子标准厅） */
    // 变更后整体失效：会议室缓存（列表 + 详情）。策略变更也在此列——列表里带 needApproval 等策略字段
    @CacheEvict(cacheNames = {CacheConfig.ROOM_LIST_CACHE, CacheConfig.ROOM_DETAIL_CACHE}, allEntries = true)
    @Transactional
    public Long create(RoomSaveDTO dto) {
        ensureGroupExists(dto.getGroupId());
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
        // 与 db/01-schema.sql 默认值、种子数据（青竹厅）保持一致：评审 P1 修复（原为 30）
        strategy.setCancelDeadlineMinutes(0);
        strategy.setBufferMinutes(0);
        strategy.setAllowRecurring(1);
        roomStrategyMapper.insert(strategy);
        return room.getId();
    }

    /** 修改（动态更新：仅提交的字段生效） */
    // 变更后整体失效：会议室缓存（列表 + 详情）。策略变更也在此列——列表里带 needApproval 等策略字段
    @CacheEvict(cacheNames = {CacheConfig.ROOM_LIST_CACHE, CacheConfig.ROOM_DETAIL_CACHE}, allEntries = true)
    @Transactional
    public void update(Long id, RoomSaveDTO dto) {
        ensureRoomExists(id);
        ensureGroupExists(dto.getGroupId());
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
    // 变更后整体失效：会议室缓存（列表 + 详情）。策略变更也在此列——列表里带 needApproval 等策略字段
    @CacheEvict(cacheNames = {CacheConfig.ROOM_LIST_CACHE, CacheConfig.ROOM_DETAIL_CACHE}, allEntries = true)
    @Transactional
    public void remove(Long id) {
        ensureRoomExists(id);
        if (meetingRoomMapper.countActiveMeetingsByRoom(id) > 0) {
            throw new BusinessException(ErrorCode.ROOM_HAS_ACTIVE_MEETINGS);
        }
        meetingRoomMapper.deleteById(id);
        roomStrategyMapper.deleteByRoomId(id);
    }

    /** 策略全量更新（原子 upsert：历史数据缺失策略时由 ON DUPLICATE KEY 兜底创建） */
    // 变更后整体失效：会议室缓存（列表 + 详情）。策略变更也在此列——列表里带 needApproval 等策略字段
    @CacheEvict(cacheNames = {CacheConfig.ROOM_LIST_CACHE, CacheConfig.ROOM_DETAIL_CACHE}, allEntries = true)
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
        roomStrategyMapper.upsertByRoomId(strategy);
    }

    private void ensureRoomExists(Long id) {
        if (meetingRoomMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }
    }

    /**
     * 分组存在性校验（评审 P1-9）：无数据库外键，悬空 group_id 会静默落库——
     * 该会议室在"按分组筛选"时永远查不到、列表里分组名为空，而管理员看不出哪里错了。
     */
    private void ensureGroupExists(Long groupId) {
        if (groupId != null && roomGroupMapper.selectById(groupId) == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "会议室分组不存在: " + groupId);
        }
    }
}
