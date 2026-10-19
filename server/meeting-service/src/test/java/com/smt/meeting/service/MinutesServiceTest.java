package com.smt.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.config.MinutesProperties;
import com.smt.meeting.entity.FileObject;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingMinutes;
import com.smt.meeting.entity.MinutesSpeaker;
import com.smt.meeting.entity.TranscriptSegment;
import com.smt.meeting.enums.FileObjectStatus;
import com.smt.meeting.mapper.FileObjectMapper;
import com.smt.meeting.mapper.MeetingMinutesMapper;
import com.smt.meeting.mapper.MinutesSpeakerMapper;
import com.smt.meeting.mapper.TranscriptSegmentMapper;
import com.smt.meeting.vo.MinutesInsightsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 纪要读侧：详情 / 转写 / 音频 URL。转写写入与 ASR 状态机在 MinutesAsrServiceTest。
 */
class MinutesServiceTest {

    private static final long MEETING_ID = 42L;
    private static final long USER_ID = 7L;

    private MeetingMinutesMapper minutesMapper;
    private MinutesSpeakerMapper speakerMapper;
    private TranscriptSegmentMapper segmentMapper;
    private FileObjectMapper fileObjectMapper;
    private MinutesPermissionService permissionService;
    private MinutesInsightsService insightsService;
    private ObjectStorage storage;
    private MinutesService service;

    @BeforeEach
    void setUp() {
        minutesMapper = mock(MeetingMinutesMapper.class);
        speakerMapper = mock(MinutesSpeakerMapper.class);
        segmentMapper = mock(TranscriptSegmentMapper.class);
        fileObjectMapper = mock(FileObjectMapper.class);
        permissionService = mock(MinutesPermissionService.class);
        insightsService = mock(MinutesInsightsService.class);
        storage = mock(ObjectStorage.class);
        service = new MinutesService(
                minutesMapper, speakerMapper, segmentMapper, fileObjectMapper,
                permissionService, insightsService,
                MinutesSummaryParser.withObjectMapper(new ObjectMapper()),
                new MinutesProperties(), storage);
    }

    @Test
    void detailReturnsNullWhenNoMinutes() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting());
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(null);

        assertThat(service.detail(MEETING_ID, USER_ID)).isNull();
        verify(permissionService).assertCanView(any(Meeting.class), eq(USER_ID));
    }

    @Test
    void detailMapsAsrAndAiState() {
        MeetingMinutes minutes = minutes();
        minutes.setAsrStatus(20);
        minutes.setAsrError("识别中");
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting());
        when(permissionService.canManage(any(Meeting.class), eq(USER_ID))).thenReturn(true);
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(minutes);
        when(speakerMapper.selectByMinutesId(10L)).thenReturn(List.of(speaker()));
        when(segmentMapper.selectByMinutesId(10L)).thenReturn(List.of(segment()));
        when(insightsService.compute(any(), any(), eq(60_000L))).thenReturn(insights());
        when(fileObjectMapper.selectById(9L)).thenReturn(file());

        var detail = service.detail(MEETING_ID, USER_ID);

        assertThat(detail.getAsrStatus()).isEqualTo(20);
        assertThat(detail.getAsrError()).isEqualTo("识别中");
        assertThat(detail.getAiProvider()).isEqualTo("deepseek");
        assertThat(detail.getSpeakers()).singleElement()
                .satisfies(speaker -> assertThat(speaker.getDisplayName()).isEqualTo("说话人 1"));
        assertThat(detail.getAudio().getFileId()).isEqualTo(9L);
        assertThat(detail.getPermissions().isCanManage()).isTrue();
    }

    @Test
    void legacyNullAsrStatusMapsToZero() {
        MeetingMinutes minutes = minutes();
        minutes.setAsrStatus(null);
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting());
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(minutes);
        when(speakerMapper.selectByMinutesId(10L)).thenReturn(List.of());
        when(segmentMapper.selectByMinutesId(10L)).thenReturn(List.of());
        when(insightsService.compute(any(), any(), eq(60_000L))).thenReturn(insights());

        assertThat(service.detail(MEETING_ID, USER_ID).getAsrStatus()).isZero();
    }

    @Test
    void transcriptMapsSegmentsInOrder() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting());
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(minutes());
        when(segmentMapper.selectByMinutesId(10L)).thenReturn(List.of(segment()));

        var transcript = service.transcript(MEETING_ID, USER_ID);

        assertThat(transcript.getSegments()).singleElement().satisfies(segment -> {
            assertThat(segment.getSeq()).isZero();
            assertThat(segment.getSpeakerKey()).isEqualTo("0");
            assertThat(segment.getText()).isEqualTo("你好");
        });
    }

    @Test
    void audioUrlUsesPresignedGet() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting());
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(minutes());
        when(fileObjectMapper.selectById(9L)).thenReturn(file());
        when(storage.presignGet("minutes/42/a.m4a", Duration.ofMinutes(60)))
                .thenReturn("https://cos.example/a");

        var audio = service.audioUrl(MEETING_ID, USER_ID);

        assertThat(audio.getUrl()).isEqualTo("https://cos.example/a");
        assertThat(audio.getExpiresAt()).isNotNull();
    }

    @Test
    void audioUrlRejectsDeletedFile() {
        FileObject deleted = file();
        deleted.setStatus(FileObjectStatus.DELETED);
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting());
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(minutes());
        when(fileObjectMapper.selectById(9L)).thenReturn(deleted);

        assertThatThrownBy(() -> service.audioUrl(MEETING_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.MINUTES_NOT_FOUND.getCode());
    }

    private Meeting meeting() {
        Meeting meeting = new Meeting();
        meeting.setId(MEETING_ID);
        meeting.setRoomId(1L);
        meeting.setOrganiserId(USER_ID);
        meeting.setStatus(40);
        return meeting;
    }

    private MeetingMinutes minutes() {
        MeetingMinutes minutes = new MeetingMinutes();
        minutes.setId(10L);
        minutes.setMeetingId(MEETING_ID);
        minutes.setRoomId(1L);
        minutes.setAudioFileId(9L);
        minutes.setTranscriptSource("TENCENT_ASR");
        minutes.setDurationMs(60_000L);
        minutes.setAsrStatus(30);
        minutes.setAiStatus(0);
        return minutes;
    }

    private MinutesSpeaker speaker() {
        MinutesSpeaker speaker = new MinutesSpeaker();
        speaker.setSpeakerKey("0");
        speaker.setDisplayName("说话人 1");
        speaker.setColor("#7D8CFF");
        return speaker;
    }

    private TranscriptSegment segment() {
        TranscriptSegment segment = new TranscriptSegment();
        segment.setId(1L);
        segment.setSeq(0);
        segment.setSpeakerKey("0");
        segment.setStartMs(0L);
        segment.setEndMs(1000L);
        segment.setText("你好");
        return segment;
    }

    private FileObject file() {
        FileObject file = new FileObject();
        file.setId(9L);
        file.setBizType("MINUTES_AUDIO");
        file.setBizId(MEETING_ID);
        file.setObjectKey("minutes/42/a.m4a");
        file.setOriginalName("a.m4a");
        file.setSizeBytes(1000L);
        file.setStatus(FileObjectStatus.NORMAL);
        return file;
    }

    private MinutesInsightsVO insights() {
        MinutesInsightsVO insights = new MinutesInsightsVO();
        insights.setDurationMs(60_000L);
        insights.setTotalSegments(1);
        return insights;
    }
}
