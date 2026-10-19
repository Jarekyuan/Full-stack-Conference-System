package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.aop.Log;
import com.smt.meeting.config.AsrProperties;
import com.smt.meeting.dto.MinutesTranscribeDTO;
import com.smt.meeting.entity.FileObject;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingMinutes;
import com.smt.meeting.entity.MinutesSpeaker;
import com.smt.meeting.entity.TranscriptSegment;
import com.smt.meeting.enums.FileBizType;
import com.smt.meeting.enums.FileObjectStatus;
import com.smt.meeting.enums.MeetingStatusEnum;
import com.smt.meeting.enums.MinutesAiStatus;
import com.smt.meeting.enums.MinutesAsrStatus;
import com.smt.meeting.mapper.FileObjectMapper;
import com.smt.meeting.mapper.MeetingMinutesMapper;
import com.smt.meeting.mapper.MinutesSpeakerMapper;
import com.smt.meeting.mapper.TranscriptSegmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 腾讯 ASR 异步转写：上传音频后自动识别并写入工作台。
 *
 * <h3>状态</h3>
 * <p>{@code asr_status}: 0 未转写 / 10 待识别 / 20 识别中 / 30 成功 / 40 失败。
 * 提交任务由 {@link com.smt.meeting.task.MinutesAsrTask} 扫描 10；20 由任务轮询
 * {@code DescribeTaskStatus}，成功后在一个事务里写 speakers + segments。</p>
 *
 * <h3>并发/令牌</h3>
 * <p>重新上传会通过 {@code startAsr} 重置状态并清空旧分段；旧 worker 的
 * {@code markAsrSuccess} 同时比对 {@code asr_status=20} 与 {@code asr_started_at}
 * run token，CAS 0 行时结果被丢弃，不会覆盖新一轮。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinutesAsrService {

    private static final List<String> SPEAKER_COLORS = List.of(
            "#7D8CFF", "#69CFF5", "#FFB020", "#52C41A",
            "#FF6D6A", "#B37FEB", "#13C2C2", "#FA8C16");
    private static final String ASR_LOCK_PREFIX = "lock:minutes:asr:";
    private static final long LOCK_WAIT_SECONDS = 3L;
    private static final int INSERT_BATCH = 500;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int DEFAULT_STUCK_TIMEOUT_MINUTES = 120;
    private static final String DEFAULT_SOURCE = "TENCENT_ASR";
    private static final String SAFE_FAILURE = "语音识别失败，请重试";

    private final MeetingMinutesMapper minutesMapper;
    private final MinutesSpeakerMapper speakerMapper;
    private final TranscriptSegmentMapper segmentMapper;
    private final FileObjectMapper fileObjectMapper;
    private final MinutesPermissionService permissionService;
    private final ObjectStorage storage;
    private final AsrClient asrClient;
    private final AsrProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final RedissonClient redisson;

    /**
     * 发起转写：校验权限/文件 → 在会议锁内创建工作台行并清空旧转写 → 交给后台 worker。
     * 同一音频在 10/20 时幂等返回当前状态。
     */
    @Log(type = "MINUTES_ASR_REQUEST")
    public Long request(Long meetingId, MinutesTranscribeDTO dto, Long userId) {
        Meeting meeting = permissionService.requireMeeting(meetingId);
        permissionService.assertCanManage(meeting, userId);
        if (!Integer.valueOf(MeetingStatusEnum.FINISHED.getCode()).equals(meeting.getStatus())) {
            throw new BusinessException(ErrorCode.MINUTES_STATUS_NOT_ALLOWED);
        }
        assertConfigured();
        requireAudioFile(meetingId, dto.getFileId());

        RLock lock = redisson.getLock(ASR_LOCK_PREFIX + meetingId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException(ErrorCode.SYSTEM_BUSY);
            }
            StartResult result = transactionTemplate.execute(
                    status -> doStart(meeting, dto.getFileId(), userId));
            if (result == null) {
                throw new BusinessException(ErrorCode.SYSTEM_BUSY);
            }
            if (result.oldFileId() != null && !result.oldFileId().equals(dto.getFileId())) {
                deleteOldFileQuietly(result.oldFileId());
            }
            return result.minutesId();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_BUSY);
        } finally {
            if (acquired) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (Exception e) {
                    log.warn("[纪要 ASR] 释放会议锁失败 meetingId={}", meetingId, e);
                }
            }
        }
    }

    private StartResult doStart(Meeting meeting, Long fileId, Long userId) {
        requireAudioFile(meeting.getId(), fileId);
        MeetingMinutes current = minutesMapper.selectByMeetingId(meeting.getId());
        if (current != null) {
            int status = current.getAsrStatus() == null
                    ? MinutesAsrStatus.NOT_STARTED : current.getAsrStatus();
            if ((status == MinutesAsrStatus.PENDING || status == MinutesAsrStatus.RUNNING)
                    && fileId.equals(current.getAudioFileId())) {
                return new StartResult(current.getId(), null);
            }
            current = minutesMapper.selectByMeetingIdForUpdate(meeting.getId());
            if (current == null) {
                throw new BusinessException(ErrorCode.MINUTES_NOT_FOUND);
            }
            Long oldFileId = current.getAudioFileId();
            minutesMapper.startAsr(current.getId(), fileId, 0L, now());
            speakerMapper.deleteByMinutesId(current.getId());
            segmentMapper.deleteByMinutesId(current.getId());
            return new StartResult(current.getId(), oldFileId);
        }
        MeetingMinutes minutes = new MeetingMinutes();
        minutes.setMeetingId(meeting.getId());
        minutes.setRoomId(meeting.getRoomId());
        minutes.setAudioFileId(fileId);
        minutes.setTranscriptSource(DEFAULT_SOURCE);
        minutes.setDurationMs(0L);
        minutes.setAiStatus(MinutesAiStatus.NOT_GENERATED.getCode());
        minutes.setCreatedBy(userId);
        minutesMapper.insert(minutes);
        minutesMapper.startAsr(minutes.getId(), fileId, 0L, now());
        return new StartResult(minutes.getId(), null);
    }

    /**
     * 处理一条 10/20 的转写任务；由 {@link com.smt.meeting.task.MinutesAsrTask}
     * 在条目锁内调用。异常不向调度线程抛出。
     */
    public void process(Long minutesId) {
        MeetingMinutes minutes;
        try {
            minutes = minutesMapper.selectById(minutesId);
        } catch (Exception e) {
            log.error("[纪要 ASR] 读取任务失败 minutesId={} type={}",
                    minutesId, failureType(e), e);
            return;
        }
        if (minutes == null) {
            return;
        }
        int status = minutes.getAsrStatus() == null
                ? MinutesAsrStatus.NOT_STARTED : minutes.getAsrStatus();
        if (status == MinutesAsrStatus.PENDING) {
            submit(minutes);
        } else if (status == MinutesAsrStatus.RUNNING) {
            poll(minutes);
        }
    }

    private void submit(MeetingMinutes minutes) {
        try {
            FileObject file = requireAudioFile(minutes.getMeetingId(), minutes.getAudioFileId());
            Duration ttl = Duration.ofHours(Math.max(1, intValue(properties.getUrlTtlHours(), 6)));
            String url = storage.presignGet(file.getObjectKey(), ttl);
            String taskId = asrClient.submit(url);
            LocalDateTime startedAt = minutes.getAsrStartedAt();
            if (minutesMapper.markAsrSubmitted(minutes.getId(), taskId, startedAt) == 0) {
                log.warn("[纪要 ASR] markAsrSubmitted CAS 未命中，任务被重新提交 minutesId={}",
                        minutes.getId());
            }
        } catch (Exception e) {
            handleSubmitFailure(minutes, e);
        }
    }

    private void poll(MeetingMinutes minutes) {
        String taskId = minutes.getAsrTaskId();
        if (taskId == null || taskId.isBlank()) {
            handleFailure(minutes, attemptsOf(minutes) + 1,
                    minutes.getAsrStartedAt(), SAFE_FAILURE + "（缺少任务号）");
            return;
        }
        AsrClient.AsrQueryResult result;
        try {
            result = asrClient.query(taskId);
        } catch (Exception e) {
            // 查询抖动：保持 20，让卡死扫描在超时后重试
            log.warn("[纪要 ASR] 查询异常 minutesId={} type={}",
                    minutes.getId(), failureType(e));
            return;
        }
        if (result.status() == AsrClient.Status.WAITING
                || result.status() == AsrClient.Status.PROCESSING) {
            return;
        }
        if (result.status() == AsrClient.Status.FAILED) {
            handleFailure(minutes, attemptsOf(minutes) + 1,
                    minutes.getAsrStartedAt(), result.error() == null
                            ? SAFE_FAILURE : result.error());
            return;
        }
        if (result.sentences() == null || result.sentences().isEmpty()) {
            handleFailure(minutes, attemptsOf(minutes) + 1,
                    minutes.getAsrStartedAt(), "未识别到有效语音");
            return;
        }
        persistSuccess(minutes, result);
    }

    private void persistSuccess(MeetingMinutes minutes, AsrClient.AsrQueryResult result) {
        LocalDateTime startedAt = minutes.getAsrStartedAt();
        boolean saved;
        try {
            saved = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                MeetingMinutes locked = minutesMapper.selectByMeetingIdForUpdate(
                        minutes.getMeetingId());
                if (locked == null
                        || !Integer.valueOf(MinutesAsrStatus.RUNNING).equals(locked.getAsrStatus())
                        || !Objects.equals(locked.getAsrStartedAt(), startedAt)) {
                    return false;
                }
                long lastEnd = result.sentences().stream()
                        .mapToLong(AsrClient.Sentence::endMs).max().orElse(0L);
                long durationMs = Math.max(result.durationMs(), lastEnd);
                if (minutesMapper.markAsrSuccess(
                        locked.getId(), durationMs, now(), startedAt) == 0) {
                    return false;
                }
                speakerMapper.deleteByMinutesId(locked.getId());
                segmentMapper.deleteByMinutesId(locked.getId());
                insertSpeakers(locked.getId(), result.sentences());
                insertSegments(locked.getId(), result.sentences());
                return true;
            }));
        } catch (Exception e) {
            // 结果已拿到但落库失败：保持 20，下一轮/卡死恢复会重新查询并重试落库
            log.error("[纪要 ASR] 写入转写失败 minutesId={} type={}",
                    minutes.getId(), failureType(e), e);
            return;
        }
        if (!saved) {
            log.warn("[纪要 ASR] 结果被丢弃（重新上传/新一轮） minutesId={}", minutes.getId());
        }
    }

    private void handleSubmitFailure(MeetingMinutes minutes, Exception cause) {
        int attempts = attemptsOf(minutes) + 1;
        int maxAttempts = maxAttempts();
        String error = SAFE_FAILURE;
        LocalDateTime startedAt = minutes.getAsrStartedAt();
        if (attempts >= maxAttempts) {
            minutesMapper.markAsrSubmitFailed(minutes.getId(), error, attempts, now(), startedAt);
        } else {
            minutesMapper.markAsrSubmitRetry(minutes.getId(), error, attempts, now(), startedAt);
        }
        log.warn("[纪要 ASR] 提交失败 minutesId={} attempts={} final={} type={}",
                minutes.getId(), attempts, attempts >= maxAttempts, failureType(cause));
    }

    private void handleFailure(MeetingMinutes minutes, int attempts,
                               LocalDateTime startedAt, String error) {
        int maxAttempts = maxAttempts();
        if (attempts >= maxAttempts) {
            minutesMapper.markAsrFailed(minutes.getId(), error, now(), attempts, startedAt);
        } else {
            minutesMapper.markAsrRetry(minutes.getId(), error, now(), attempts, startedAt);
        }
        log.warn("[纪要 ASR] 识别失败 minutesId={} attempts={} final={}",
                minutes.getId(), attempts, attempts >= maxAttempts);
    }

    /** 至少 2 分钟才认为卡死；配置值 <=0 时使用默认值。 */
    public void handleStuck(Long minutesId) {
        MeetingMinutes minutes;
        try {
            minutes = minutesMapper.selectById(minutesId);
        } catch (Exception e) {
            log.error("[纪要 ASR] 读取卡死任务失败 minutesId={} type={}",
                    minutesId, failureType(e), e);
            return;
        }
        if (minutes == null || !Integer.valueOf(MinutesAsrStatus.RUNNING)
                .equals(minutes.getAsrStatus())) {
            return;
        }
        LocalDateTime startedAt = minutes.getAsrStartedAt();
        LocalDateTime before = now().minusMinutes(stuckTimeoutMinutes());
        if (startedAt != null && !startedAt.isBefore(before)) {
            return;
        }
        handleFailure(minutes, attemptsOf(minutes) + 1, startedAt,
                "语音识别超时，请重试");
    }

    private void insertSpeakers(Long minutesId, List<AsrClient.Sentence> sentences) {
        Set<String> keys = new LinkedHashSet<>();
        for (AsrClient.Sentence sentence : sentences) {
            keys.add(sentence.speakerKey());
        }
        List<MinutesSpeaker> speakers = new ArrayList<>(keys.size());
        int index = 0;
        for (String key : keys) {
            MinutesSpeaker speaker = new MinutesSpeaker();
            speaker.setMinutesId(minutesId);
            speaker.setSpeakerKey(key);
            speaker.setDisplayName("说话人 " + (index + 1));
            speaker.setColor(SPEAKER_COLORS.get(index % SPEAKER_COLORS.size()));
            speakers.add(speaker);
            index++;
        }
        if (!speakers.isEmpty()) {
            speakerMapper.batchInsert(speakers);
        }
    }

    private void insertSegments(Long minutesId, List<AsrClient.Sentence> sentences) {
        List<TranscriptSegment> segments = new ArrayList<>(sentences.size());
        int seq = 0;
        for (AsrClient.Sentence sentence : sentences) {
            TranscriptSegment segment = new TranscriptSegment();
            segment.setMinutesId(minutesId);
            segment.setSeq(seq++);
            segment.setSpeakerKey(sentence.speakerKey());
            segment.setStartMs(sentence.startMs());
            segment.setEndMs(sentence.endMs());
            segment.setText(sentence.text());
            segments.add(segment);
            if (segments.size() >= INSERT_BATCH) {
                segmentMapper.batchInsert(segments);
                segments.clear();
            }
        }
        if (!segments.isEmpty()) {
            segmentMapper.batchInsert(segments);
        }
    }

    private FileObject requireAudioFile(Long meetingId, Long fileId) {
        FileObject file = fileObjectMapper.selectById(fileId);
        if (file == null
                || !FileBizType.MINUTES_AUDIO.equals(file.getBizType())
                || !meetingId.equals(file.getBizId())
                || !Integer.valueOf(FileObjectStatus.NORMAL).equals(file.getStatus())) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED.getCode(),
                    "音频文件不属于该会议或已删除");
        }
        return file;
    }

    private void deleteOldFileQuietly(Long oldFileId) {
        try {
            FileObject old = fileObjectMapper.selectById(oldFileId);
            if (old == null || old.getObjectKey() == null) {
                return;
            }
            storage.delete(old.getObjectKey());
            fileObjectMapper.markDeleted(oldFileId);
        } catch (Exception e) {
            log.warn("[纪要 ASR] 清理旧音频失败 fileId={} type={}",
                    oldFileId, failureType(e));
        }
    }

    private void assertConfigured() {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            throw new BusinessException(ErrorCode.ASR_NOT_CONFIGURED);
        }
        if (!"mock".equalsIgnoreCase(properties.getProvider())
                && (properties.getSecretId() == null || properties.getSecretId().isBlank()
                || properties.getSecretKey() == null || properties.getSecretKey().isBlank())) {
            throw new BusinessException(ErrorCode.ASR_NOT_CONFIGURED);
        }
    }

    private int attemptsOf(MeetingMinutes minutes) {
        return minutes.getAsrAttempts() == null ? 0 : minutes.getAsrAttempts();
    }

    private int maxAttempts() {
        return Math.max(1, intValue(properties.getMaxAttempts(), DEFAULT_MAX_ATTEMPTS));
    }

    private int stuckTimeoutMinutes() {
        return Math.max(2, intValue(properties.getStuckTimeoutMinutes(),
                DEFAULT_STUCK_TIMEOUT_MINUTES));
    }

    private static int intValue(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().withNano(0);
    }

    private static String failureType(Exception e) {
        return e.getClass().getSimpleName();
    }

    private record StartResult(Long minutesId, Long oldFileId) {
    }
}
