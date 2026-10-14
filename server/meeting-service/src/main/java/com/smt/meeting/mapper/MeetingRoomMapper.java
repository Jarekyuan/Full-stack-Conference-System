package com.smt.meeting.mapper;

import com.smt.meeting.entity.MeetingRoom;
import com.smt.meeting.vo.RoomVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会议室 Mapper（SQL 手写 XML：resources/mapper/MeetingRoomMapper.xml）
 */
public interface MeetingRoomMapper {

    /** 全部会议室（按 id 排序；images/deviceTags 经 JsonTypeHandler 解析） */
    List<MeetingRoom> selectAll();

    /** 按主键查询 */
    MeetingRoom selectById(@Param("id") Long id);

    /** 列表联查（含分组名与策略；动态筛选：groupId 精确 / capacity 下限 / status 精确） */
    List<RoomVO> selectList(@Param("groupId") Long groupId,
                            @Param("capacity") Integer capacity,
                            @Param("status") Integer status);

    /** 详情联查（含分组名与策略） */
    RoomVO selectDetailById(@Param("id") Long id);

    /** 新增会议室（自增回填 id） */
    int insert(MeetingRoom room);

    /** 更新（动态：仅更新非空字段；status 提供才修改） */
    int update(MeetingRoom room);

    /** 物理删除 */
    int deleteById(@Param("id") Long id);

    /** 统计该会议室未结束的会议数（10 待审批/20 已预约/30 进行中）——删除前校验 */
    long countActiveMeetingsByRoom(@Param("roomId") Long roomId);
}
