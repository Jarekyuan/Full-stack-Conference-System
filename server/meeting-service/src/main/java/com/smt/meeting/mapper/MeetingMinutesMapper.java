package com.smt.meeting.mapper;

import com.smt.meeting.entity.MeetingMinutes;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会议纪要 Mapper（SQL 手写 XML：resources/mapper/MeetingMinutesMapper.xml）
 *
 * <p>AI 状态迁移通常带前态条件（CAS），影响 0 行 = 状态已被并发变更。
 * 重新导入/重新转写通过 {@link #startAsr} 在同一事务内清空 AI 结果；
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

    /** 请求生成：0/30/40 → 10 待生成 */
    int requestGenerate(@Param("id") Long id);

    /** 开始生成：10 → 20 生成中（清空上一轮结束时间） */
    int markRunning(@Param("id") Long id, @Param("startedAt") LocalDateTime startedAt);

    /**
     * 生成成功：20 → 30 成功。
     *
     * <p>{@code startedAt} 是本次生成的令牌（= markRunning 写入的 ai_started_at）。
     * CAS 同时比对状态与令牌：旧 worker 即使还停在 20，也无法覆盖新一轮生成的结果。</p>
     */
    int markSuccess(@Param("id") Long id,
                    @Param("summaryJson") String summaryJson,
                    @Param("model") String model,
                    @Param("finishedAt") LocalDateTime finishedAt,
                    @Param("startedAt") LocalDateTime startedAt);

    /** 生成失败待重试：20 → 10 待生成（清空开始时间，下次 markRunning 重新计时） */
    int markRetry(@Param("id") Long id,
                  @Param("error") String error,
                  @Param("finishedAt") LocalDateTime finishedAt,
                  @Param("attempts") int attempts,
                  @Param("startedAt") LocalDateTime startedAt);

    /** 生成失败终止：20 → 40 失败 */
    int markFailed(@Param("id") Long id,
                   @Param("error") String error,
                   @Param("finishedAt") LocalDateTime finishedAt,
                   @Param("attempts") int attempts,
                   @Param("startedAt") LocalDateTime startedAt);

    /** 待生成队列（ai_status=10，命中 idx_ai_status） */
    List<MeetingMinutes> selectAiPending(@Param("limit") int limit);

    /** 生成超时（ai_status=20 且 ai_started_at 早于阈值） */
    List<MeetingMinutes> selectAiStuck(@Param("before") LocalDateTime before,
                                       @Param("limit") int limit);

    // ---------- 腾讯 ASR 转写 ----------

    /** 开始/重开转写：绑定音频、清空旧转写与 AI 结果，进入 10 待识别 */
    int startAsr(@Param("id") Long id,
                 @Param("audioFileId") Long audioFileId,
                 @Param("durationMs") long durationMs,
                 @Param("startedAt") LocalDateTime startedAt);

    /** 已提交 ASR：10 → 20，写入 TaskId 与 run token */
    int markAsrSubmitted(@Param("id") Long id,
                         @Param("taskId") String taskId,
                         @Param("startedAt") LocalDateTime startedAt);

    /** 提交 ASR 失败待重试：保持 10，记录次数与错误 */
    int markAsrSubmitRetry(@Param("id") Long id,
                           @Param("error") String error,
                           @Param("attempts") int attempts,
                           @Param("finishedAt") LocalDateTime finishedAt,
                           @Param("startedAt") LocalDateTime startedAt);

    /** 提交 ASR 失败终止：10 → 40 */
    int markAsrSubmitFailed(@Param("id") Long id,
                            @Param("error") String error,
                            @Param("attempts") int attempts,
                            @Param("finishedAt") LocalDateTime finishedAt,
                            @Param("startedAt") LocalDateTime startedAt);

    /** 识别成功：20 → 30；TaskId 与 startedAt 共同作为本轮令牌 */
    int markAsrSuccess(@Param("id") Long id,
                       @Param("durationMs") long durationMs,
                       @Param("finishedAt") LocalDateTime finishedAt,
                       @Param("startedAt") LocalDateTime startedAt);

    /** 识别失败待重试：20 → 10，清空 TaskId，下一轮重新提交 */
    int markAsrRetry(@Param("id") Long id,
                     @Param("error") String error,
                     @Param("finishedAt") LocalDateTime finishedAt,
                     @Param("attempts") int attempts,
                     @Param("startedAt") LocalDateTime startedAt);

    /** 识别失败终止：20 → 40 */
    int markAsrFailed(@Param("id") Long id,
                      @Param("error") String error,
                      @Param("finishedAt") LocalDateTime finishedAt,
                      @Param("attempts") int attempts,
                      @Param("startedAt") LocalDateTime startedAt);

    /** 待提交 ASR 队列（asr_status=10） */
    List<MeetingMinutes> selectAsrPending(@Param("limit") int limit);

    /** 识别中队列（asr_status=20），每轮轮询供应商状态 */
    List<MeetingMinutes> selectAsrRunning(@Param("limit") int limit);

    /** ASR 超时（asr_status=20 且 asr_started_at 早于阈值） */
    List<MeetingMinutes> selectAsrStuck(@Param("before") LocalDateTime before,
                                        @Param("limit") int limit);
}
