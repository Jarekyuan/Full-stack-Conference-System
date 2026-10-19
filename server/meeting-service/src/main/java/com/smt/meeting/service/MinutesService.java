package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.config.MinutesProperties;
import com.smt.meeting.entity.FileObject;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingMinutes;
import com.smt.meeting.entity.MinutesSpeaker;
import com.smt.meeting.entity.TranscriptSegment;
import com.smt.meeting.enums.FileObjectStatus;
import com.smt.meeting.enums.MinutesAsrStatus;
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
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会议纪要查询服务：详情、转写、音频预签名 URL。
 *
 * <p>转写写入与状态机在 {@link MinutesAsrService}；本类只负责读侧，
 * 避免详情/转写/播放被异步识别流程牵连。</p>
 */
@Service
@RequiredArgsConstructor
public class MinutesService {

    private static final Duration AUDIO_URL_TTL = Duration.ofMinutes(60);

    private final MeetingMinutesMapper minutesMapper;
    private final MinutesSpeakerMapper speakerMapper;
    private final TranscriptSegmentMapper segmentMapper;
    private final FileObjectMapper fileObjectMapper;
    private final MinutesPermissionService permissionService;
    private final MinutesInsightsService insightsService;
    private final MinutesSummaryParser summaryParser;
    private final MinutesProperties properties;
    private final ObjectStorage storage;

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
        vo.setAsrStatus(minutes.getAsrStatus() == null
                ? MinutesAsrStatus.NOT_STARTED : minutes.getAsrStatus());
        vo.setAsrError(minutes.getAsrError());
        vo.setAiStatus(minutes.getAiStatus());
        vo.setAiError(minutes.getAiError());
        vo.setAiProvider(properties.getAi().getProvider());
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

}
