package com.smt.meeting.mapper;

import com.smt.meeting.entity.MeetingMinutes;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会议纪要 Mapper（SQL 手写 XML：resources/mapper/MeetingMinutesMapper.xml）
 *
 * <p>AI 状态迁移通常带前态条件（CAS），影响 0 行 = 状态已被并发变更。
 * 唯一例外是 {@link #resetAi(Long)}：重新导入时无条件作废上一次生成结果；
 * 若此时仍有 worker 停在生成中(20)，其 markSuccess 的 CAS 会以 0 行收场，这是预期行为（结果被丢弃）。
 */
public interface MeetingMinutesMapper {

    /** 按主键查询 */
    MeetingMinutes selectById(@Param("id") Long id);

    /** 按会议查询（一场会议一份，命中 uk_meeting） */
    MeetingMinutes selectByMeetingId(@Param("meetingId") Long meetingId);

    /**
     * 事务内锁住纪要行（SELECT ... FOR UPDATE）。
     *
     * <p>⚠️ 行不存在时，InnoDB 取的是 gap lock：两个并发的"首次导入"可能各自持有 gap lock
     * 再同时 INSERT，形成死锁。因此该语句不能作为首次导入的串行化手段——导入路径（Task 8）
     * 必须按 meetingId 加 Redisson 锁（锁包住事务），或显式处理 duplicate-key/死锁重试。
     */
    MeetingMinutes selectByMeetingIdForUpdate(@Param("meetingId") Long meetingId);

    /** 新增纪要（自增 id 回填到实体） */
    int insert(MeetingMinutes minutes);

    /** 导入录音后回填音频文件与时长（不触碰 AI 状态） */
    int updateImport(@Param("id") Long id,
                     @Param("audioFileId") Long audioFileId,
                     @Param("durationMs") long durationMs,
                     @Param("transcriptSource") String transcriptSource);

    /**
     * 重置 AI 生成结果与状态（回到 0 未生成）。重新导入时**无条件作废**上一次生成结果；
     * 若此时仍有 worker 停在生成中(20)，其 markSuccess 的 CAS（20）会以 0 行收场，结果被有意丢弃。
     */
    int resetAi(@Param("id") Long id);

    /** 请求生成：0/30/40 → 10 待生成 */
    int requestGenerate(@Param("id") Long id);

    /** 开始生成：10 → 20 生成中（清空上一轮结束时间） */
    int markRunning(@Param("id") Long id, @Param("startedAt") LocalDateTime startedAt);

    /** 生成成功：20 → 30 成功 */
    int markSuccess(@Param("id") Long id,
                    @Param("summaryJson") String summaryJson,
                    @Param("model") String model,
                    @Param("finishedAt") LocalDateTime finishedAt);

    /** 生成失败待重试：20 → 10 待生成（清空开始时间，下次 markRunning 重新计时） */
    int markRetry(@Param("id") Long id,
                  @Param("error") String error,
                  @Param("finishedAt") LocalDateTime finishedAt,
                  @Param("attempts") int attempts);

    /** 生成失败终止：20 → 40 失败 */
    int markFailed(@Param("id") Long id,
                   @Param("error") String error,
                   @Param("finishedAt") LocalDateTime finishedAt,
                   @Param("attempts") int attempts);

    /** 待生成队列（ai_status=10，命中 idx_ai_status） */
    List<MeetingMinutes> selectAiPending(@Param("limit") int limit);

    /** 生成超时（ai_status=20 且 ai_started_at 早于阈值） */
    List<MeetingMinutes> selectAiStuck(@Param("before") LocalDateTime before,
                                       @Param("limit") int limit);
}
