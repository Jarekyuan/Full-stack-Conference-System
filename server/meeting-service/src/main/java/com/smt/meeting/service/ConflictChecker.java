package com.smt.meeting.service;

import com.smt.meeting.entity.RoomStrategy;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.RoomStrategyMapper;
import com.smt.meeting.vo.MeetingConflictVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 冲突检测核心（docs/01 R-3 半开区间 + R-13 锁定缓冲；docs/03 4.1 SQL）
 *
 * 【半开区间判重】占用区间语义为 [start, end)：
 *   两区间重叠 ⟺ existing.start < new.end AND existing.end > new.start
 *   → 相邻时段（10:00-11:00 与 11:00-12:00）**不算冲突**，因为 11:00 > 11:00 为假。
 *   这样"上一场结束即下一场开始"是合法的，符合会议室使用的真实语义。
 *
 * 【锁定缓冲 R-13】开启 buffer 后，检测区间扩展为 [start - buffer, end + buffer)，
 *   等价于把已有会议的占用范围前后各放宽 buffer 分钟——缓冲区间同样参与判重。
 *   （数学等价：把扫描区间放宽，与把每个已有区间放宽，判重结果一致，但前者只需算一次。）
 *
 * 本类为纯查询、无锁：可用性查询（T2.3）与创建校验（T2.4）复用；
 * 并发下的最终保证由创建链路的分布式锁 + 锁内复检承担（"查询与提交分离"，docs/04 7.4）。
 */
@Component
@RequiredArgsConstructor
public class ConflictChecker {

    private final MeetingMapper meetingMapper;
    private final RoomStrategyMapper roomStrategyMapper;

    /**
     * 检测 [start, end) 与会议室已有会议是否重叠。
     *
     * @param excludeMeetingId 修改会议场景排除自身（创建传 null）
     * @return 冲突会议简报列表（空 = 无冲突）
     */
    public List<MeetingConflictVO> findConflicts(Long roomId, LocalDateTime start, LocalDateTime end,
                                                 Long excludeMeetingId) {
        return findConflicts(roomId, start, end, excludeMeetingId, bufferMinutes(roomId));
    }

    /**
     * 批量/周期场景复用同一个 buffer（T5.1 评审 O2）：一次系列创建要检 N 场，
     * 每场都查一次 room_strategy 是 N 次无谓查询；调用方先取一次再传进来。
     */
    public List<MeetingConflictVO> findConflicts(Long roomId, LocalDateTime start, LocalDateTime end,
                                                 Long excludeMeetingId, int buffer) {
        LocalDateTime scanStart = start.minusMinutes(buffer);
        LocalDateTime scanEnd = end.plusMinutes(buffer);
        return meetingMapper.selectConflicts(roomId, scanStart, scanEnd, excludeMeetingId);
    }

    /** 该会议室锁定缓冲分钟数（无策略或未配置按 0） */
    public int bufferMinutes(Long roomId) {
        RoomStrategy strategy = roomStrategyMapper.selectByRoomId(roomId);
        if (strategy == null || strategy.getBufferMinutes() == null) {
            return 0;
        }
        return strategy.getBufferMinutes();
    }
}
