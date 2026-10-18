package com.smt.meeting.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.smt.meeting.vo.MyMeetingStatsVO;
import com.smt.meeting.vo.RoomUsageRow;

/**
 * 统计聚合 Mapper（T5.3，SQL 手写 XML：resources/mapper/StatsMapper.xml）。
 * 与其它 Mapper 分开：这里是**聚合查询**（group by），与增删改查的职责不同，放一起会让那个文件越来越杂。
 */
public interface StatsMapper {

    /** 各会议室在区间内的会议数与时长（时长按区间裁剪） */
    List<RoomUsageRow> selectRoomUsage(@Param("rangeStart") LocalDateTime rangeStart,
                                           @Param("rangeEnd") LocalDateTime rangeEnd);

    /**
     * 已删除会议室的历史会议（T5.3 评审 R8）：会议室硬删后，meeting 行还在；
     * 从 meeting 出发 LEFT JOIN 当前房间，r.id IS NULL 的就是“已删除房间”的历史。
     * 房间名用 `已删除会议室#id` 兜底，保证统计不静默丢失。
     */
    List<RoomUsageRow> selectDeletedRoomUsage(@Param("rangeStart") LocalDateTime rangeStart,
                                              @Param("rangeEnd") LocalDateTime rangeEnd);

    /** 我（组织或参与）在区间内的逐日会议数与时长 */
    List<MyMeetingStatsVO.Daily> selectMyDailyStats(@Param("userId") Long userId,
                                                    @Param("rangeStart") LocalDateTime rangeStart,
                                                    @Param("rangeEnd") LocalDateTime rangeEnd);
}
