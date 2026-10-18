package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.aop.Log;
import com.smt.meeting.dto.MinutesImportDTO;
import com.smt.meeting.dto.SpeakerUpdateDTO;
import com.smt.meeting.entity.FileObject;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingMinutes;
import com.smt.meeting.entity.MinutesSpeaker;
import com.smt.meeting.entity.TranscriptSegment;
import com.smt.meeting.enums.FileBizType;
import com.smt.meeting.enums.FileObjectStatus;
import com.smt.meeting.enums.MeetingStatusEnum;
import com.smt.meeting.enums.MinutesAiStatus;
import com.smt.meeting.mapper.FileObjectMapper;
import com.smt.meeting.mapper.MeetingMinutesMapper;
import com.smt.meeting.mapper.MinutesSpeakerMapper;
import com.smt.meeting.mapper.TranscriptSegmentMapper;
import com.smt.meeting.vo.AudioUrlVO;
import com.smt.meeting.vo.MinutesDetailVO;
import com.smt.meeting.vo.MinutesPermissionVO;
import com.smt.meeting.vo.MinutesSpeakerVO;
import com.smt.meeting.vo.TranscriptSegmentVO;
import com.smt.meeting.vo.TranscriptVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 会议纪要主服务（Task 8）：详情、转写、导入覆盖、发言人重命名、音频预签名 URL。
 *
 * <h3>导入的并发控制（关键）</h3>
 * <p>同一场会议的首次导入由 {@code lock:minutes:import:{meetingId}} 这把 Redisson 锁串行化，
 * 且锁包住事务（锁外开、事务锁内提）。原因：{@code SELECT ... FOR UPDATE} 在纪要行<b>不存在</b>时
 * 取的是 gap lock，两个并发首次导入可能各自持 gap lock 再同时 INSERT 而死锁；因此事务内先
 * {@code selectByMeetingId} 判断行是否存在，只有已存在时才升级为 {@code selectByMeetingIdForUpdate}。
 * 首次导入仅靠 Redisson 锁 + {@code uk_meeting} 唯一键保证只落一行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinutesService {

    private static final List<String> SPEAKER_COLORS = List.of(
            "#7D8CFF", "#69CFF5", "#FFB020", "#52C41A",
            "#FF6D6A", "#B37FEB", "#13C2C2", "#FA8C16");
    private static final int INSERT_BATCH = 500;
    private static final long MAX_DURATION_MS = 86_400_000L;
    private static final long LOCK_WAIT_SECONDS = 3L;
    private static final String IMPORT_LOCK_PREFIX = "lock:minutes:import:";
    private static final String DEFAULT_SOURCE = "TENCENT_ASR";
    private static final Duration AUDIO_URL_TTL = Duration.ofMinutes(60);

    private final MeetingMinutesMapper minutesMapper;
    private final MinutesSpeakerMapper speakerMapper;
    private final TranscriptSegmentMapper segmentMapper;
    private final FileObjectMapper fileObjectMapper;
    private final MinutesPermissionService permissionService;
    private final MinutesInsightsService insightsService;
    private final MinutesSummaryParser summaryParser;
    private final TranscriptParser transcriptParser;
    private final ObjectStorage storage;
    private final TransactionTemplate transactionTemplate;
    private final RedissonClient redisson;

    /**
     * 纪要详情：无纪要返回 null（与其它纪要子接口不同，不抛 70001）。
     * summary_json 采用宽松解析——历史脏数据不应让整个详情页失败。
     */
    public MinutesDetailVO detail(Long meetingId, Long userId) {
        Meeting meeting = permissionService.requireMeeting(meetingId);
        permissionService.assertCanView(meeting, userId);
        MeetingMinutes minutes = minutesMapper.selectByMeetingId(meetingId);
        if (minutes == null) {
            return null;
        }
        List<MinutesSpeaker> speakers = speakerMapper.selectByMinutesId(minutes.getId());
        List<TranscriptSegment> segments = segmentMapper.selectByMinutesId(minutes.getId());

        MinutesDetailVO vo = new MinutesDetailVO();
        vo.setId(minutes.getId());
        vo.setMeetingId(minutes.getMeetingId());
        vo.setTranscriptSource(minutes.getTranscriptSource());
        vo.setAiStatus(minutes.getAiStatus());
        vo.setAiError(minutes.getAiError());
        vo.setAiModel(minutes.getAiModel());
        vo.setSummary(summaryParser.parseOrNull(minutes.getSummaryJson()));
        vo.setSpeakers(speakers.stream()
                .map(speaker -> new MinutesSpeakerVO(
                        speaker.getSpeakerKey(), speaker.getDisplayName(), speaker.getColor()))
                .toList());
        long durationMs = minutes.getDurationMs() == null ? 0L : minutes.getDurationMs();
        vo.setInsights(insightsService.compute(speakers, segments, durationMs));
        vo.setPermissions(new MinutesPermissionVO(
                permissionService.canManage(meeting, userId), true));
        vo.setCreatedAt(minutes.getCreatedAt());
        vo.setUpdatedAt(minutes.getUpdatedAt());
        vo.setAudio(buildAudio(minutes));
        return vo;
    }

    /** 完整转写：按 seq 顺序返回全部分段。 */
    public TranscriptVO transcript(Long meetingId, Long userId) {
        Meeting meeting = permissionService.requireMeeting(meetingId);
        permissionService.assertCanView(meeting, userId);
        MeetingMinutes minutes = requireMinutes(meetingId);
        List<TranscriptSegmentVO> segments = segmentMapper.selectByMinutesId(minutes.getId())
                .stream()
                .map(segment -> {
                    TranscriptSegmentVO vo = new TranscriptSegmentVO();
                    vo.setId(segment.getId());
                    vo.setSeq(segment.getSeq());
                    vo.setSpeakerKey(segment.getSpeakerKey());
                    vo.setStartMs(segment.getStartMs());
                    vo.setEndMs(segment.getEndMs());
                    vo.setText(segment.getText());
                    return vo;
                })
                .toList();
        return new TranscriptVO(segments);
    }

    /**
     * 导入/覆盖转写与音频关联。
     *
     * <p>顺序：权限与状态 → 文件归属 → 解析转写（大小/格式/上限由 parser 负责）→ 时长 →
     * Redisson 锁（锁包住事务）→ 事务内写库 → 事务提交后清理旧音频对象。</p>
     */
    @Log(type = "MINUTES_IMPORT")
    public Long importMinutes(Long meetingId, MinutesImportDTO dto, Long userId) {
        Meeting meeting = permissionService.requireMeeting(meetingId);
        permissionService.assertCanManage(meeting, userId);
        if (!Integer.valueOf(MeetingStatusEnum.FINISHED.getCode()).equals(meeting.getStatus())) {
            throw new BusinessException(ErrorCode.MINUTES_STATUS_NOT_ALLOWED);
        }
        // 锁前快速失败（避免为明显非法的文件做解析/抢锁）；拿到锁后还会再读一次并复查状态。
        requireImportableFile(meetingId, dto.getFileId());

        TranscriptParser.ParseResult parsed = transcriptParser.parse(dto.getTranscriptText());
        long requestedDuration = dto.getDurationMs() == null ? 0L : dto.getDurationMs();
        long durationMs = Math.max(requestedDuration, parsed.lastEndMs());
        if (durationMs > MAX_DURATION_MS) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "录音时长超过 24 小时");
        }
        String source = dto.getSource() == null || dto.getSource().isBlank()
                ? DEFAULT_SOURCE : dto.getSource();

        RLock lock = redisson.getLock(IMPORT_LOCK_PREFIX + meetingId);
        // tryLock 放进 try/finally：若 Redis 在服务端已加锁后超时抛异常，
        // finally 仍会按 isHeldByCurrentThread 释放，避免锁泄漏到看门狗租约之外。
        try {
            boolean acquired;
            try {
                acquired = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.SYSTEM_BUSY);
            }
            if (!acquired) {
                throw new BusinessException(ErrorCode.SYSTEM_BUSY);
            }
            ImportResult result = transactionTemplate.execute(status ->
                    doImport(meeting, dto, userId, parsed, durationMs, source));
            // 事务已提交：旧对象删除失败只记日志，不回滚本次导入。
            if (result != null && result.oldFileId() != null
                    && !result.oldFileId().equals(dto.getFileId())) {
                deleteOldFileQuietly(result.oldFileId());
            }
            return result == null ? null : result.minutesId();
        } finally {
            releaseQuietly(lock);
        }
    }

    /**
     * 事务内写入（由 Redisson 锁包住）：
     * 先按 meetingId 普通查询判断行是否存在，只有存在时才 {@code FOR UPDATE}；
     * 不存在则直接 INSERT（gap lock 会与并发首次导入互相死锁，不能用于首次导入）。
     */
    private ImportResult doImport(Meeting meeting, MinutesImportDTO dto, Long userId,
                                  TranscriptParser.ParseResult parsed,
                                  long durationMs, String source) {
        Long meetingId = meeting.getId();
        // 锁内复查文件：并发重新导入可能在上面的锁前校验之后把该文件标记删除。
        requireImportableFile(meetingId, dto.getFileId());

        MeetingMinutes current = minutesMapper.selectByMeetingId(meetingId);
        if (current != null) {
            current = minutesMapper.selectByMeetingIdForUpdate(meetingId);
        }

        Long minutesId;
        Long oldFileId = null;
        if (current != null) {
            minutesId = current.getId();
            oldFileId = current.getAudioFileId();
            minutesMapper.updateImport(minutesId, dto.getFileId(), durationMs, source);
        } else {
            MeetingMinutes minutes = new MeetingMinutes();
            minutes.setMeetingId(meetingId);
            minutes.setRoomId(meeting.getRoomId());
            minutes.setAudioFileId(dto.getFileId());
            minutes.setTranscriptSource(source);
            minutes.setDurationMs(durationMs);
            minutes.setAiStatus(MinutesAiStatus.NOT_GENERATED.getCode());
            minutes.setCreatedBy(userId);
            minutesMapper.insert(minutes);
            minutesId = minutes.getId();
        }

        speakerMapper.deleteByMinutesId(minutesId);
        segmentMapper.deleteByMinutesId(minutesId);
        insertSpeakers(minutesId, parsed.speakerKeys());
        insertSegments(minutesId, parsed.segments());
        minutesMapper.resetAi(minutesId);
        return new ImportResult(minutesId, oldFileId);
    }

    @Log(type = "MINUTES_SPEAKER_RENAME")
    public MinutesSpeakerVO renameSpeaker(Long meetingId, String speakerKey,
                                          SpeakerUpdateDTO dto, Long userId) {
        Meeting meeting = permissionService.requireMeeting(meetingId);
        permissionService.assertCanManage(meeting, userId);
        MeetingMinutes minutes = requireMinutes(meetingId);
        int updated = speakerMapper.updateDisplayName(
                minutes.getId(), speakerKey, dto.getDisplayName(), dto.getColor());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "发言人不存在");
        }
        return new MinutesSpeakerVO(speakerKey, dto.getDisplayName(), dto.getColor());
    }

    /** 音频播放地址：私有桶对象用 60 分钟预签名 GET，前端过期后重新获取。 */
    public AudioUrlVO audioUrl(Long meetingId, Long userId) {
        Meeting meeting = permissionService.requireMeeting(meetingId);
        permissionService.assertCanView(meeting, userId);
        MeetingMinutes minutes = requireMinutes(meetingId);
        if (minutes.getAudioFileId() == null) {
            throw new BusinessException(ErrorCode.MINUTES_NOT_FOUND);
        }
        FileObject file = fileObjectMapper.selectById(minutes.getAudioFileId());
        if (file == null || !Integer.valueOf(FileObjectStatus.NORMAL).equals(file.getStatus())) {
            throw new BusinessException(ErrorCode.MINUTES_NOT_FOUND);
        }
        String url = storage.presignGet(file.getObjectKey(), AUDIO_URL_TTL);
        return new AudioUrlVO(url, LocalDateTime.now().plus(AUDIO_URL_TTL));
    }

    /**
     * 校验音频文件可绑定到该会议：存在、业务类型正确、归属该会议且未删除。
     * 导入路径调用两次——锁前快速失败 + 锁内复查（防并发删除）。
     */
    private FileObject requireImportableFile(Long meetingId, Long fileId) {
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

    private MeetingMinutes requireMinutes(Long meetingId) {
        MeetingMinutes minutes = minutesMapper.selectByMeetingId(meetingId);
        if (minutes == null) {
            throw new BusinessException(ErrorCode.MINUTES_NOT_FOUND);
        }
        return minutes;
    }

    private MinutesDetailVO.AudioVO buildAudio(MeetingMinutes minutes) {
        if (minutes.getAudioFileId() == null) {
            return null;
        }
        FileObject file = fileObjectMapper.selectById(minutes.getAudioFileId());
        if (file == null || !Integer.valueOf(FileObjectStatus.NORMAL).equals(file.getStatus())) {
            return null;
        }
        MinutesDetailVO.AudioVO audio = new MinutesDetailVO.AudioVO();
        audio.setFileId(file.getId());
        audio.setFileName(file.getOriginalName());
        audio.setSizeBytes(file.getSizeBytes());
        audio.setDurationMs(minutes.getDurationMs());
        return audio;
    }

    /** 发言人按首次出现顺序建号：默认「说话人 N」，颜色按固定调色板循环。 */
    private void insertSpeakers(Long minutesId, List<String> speakerKeys) {
        List<MinutesSpeaker> speakers = new ArrayList<>();
        for (int i = 0; i < speakerKeys.size(); i++) {
            MinutesSpeaker speaker = new MinutesSpeaker();
            speaker.setMinutesId(minutesId);
            speaker.setSpeakerKey(speakerKeys.get(i));
            speaker.setDisplayName("说话人 " + (i + 1));
            speaker.setColor(SPEAKER_COLORS.get(i % SPEAKER_COLORS.size()));
            speakers.add(speaker);
        }
        if (!speakers.isEmpty()) {
            speakerMapper.batchInsert(speakers);
        }
    }

    /** 分段按 500 条一批写入，避免单条 SQL 超出 max_allowed_packet。 */
    private void insertSegments(Long minutesId, List<TranscriptParser.ParsedSegment> parsed) {
        List<TranscriptSegment> segments = parsed.stream().map(source -> {
            TranscriptSegment segment = new TranscriptSegment();
            segment.setMinutesId(minutesId);
            segment.setSeq(source.seq());
            segment.setSpeakerKey(source.speakerKey());
            segment.setStartMs(source.startMs());
            segment.setEndMs(source.endMs());
            segment.setText(source.text());
            return segment;
        }).toList();
        for (int start = 0; start < segments.size(); start += INSERT_BATCH) {
            segmentMapper.batchInsert(
                    segments.subList(start, Math.min(start + INSERT_BATCH, segments.size())));
        }
    }

    /** 事务提交后清理旧音频：文件元数据置删除，对象删除 best-effort。 */
    private void deleteOldFileQuietly(Long fileId) {
        try {
            FileObject old = fileObjectMapper.selectById(fileId);
            fileObjectMapper.markDeleted(fileId);
            if (old != null) {
                storage.delete(old.getObjectKey());
            }
        } catch (Exception e) {
            log.warn("[纪要导入] 清理旧音频失败，fileId={}（不影响本次导入）", fileId, e);
        }
    }

    private void releaseQuietly(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("[纪要导入] 释放导入锁失败，key={}", lock.getName(), e);
        }
    }

    private record ImportResult(Long minutesId, Long oldFileId) {
    }
}
