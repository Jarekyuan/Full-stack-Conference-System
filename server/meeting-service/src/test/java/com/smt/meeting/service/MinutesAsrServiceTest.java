package com.smt.meeting.service;

import com.smt.meeting.config.AsrProperties;
import com.smt.meeting.dto.MinutesTranscribeDTO;
import com.smt.meeting.entity.FileObject;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingMinutes;
import com.smt.meeting.enums.FileObjectStatus;
import com.smt.meeting.mapper.FileObjectMapper;
import com.smt.meeting.mapper.MeetingMinutesMapper;
import com.smt.meeting.mapper.MinutesSpeakerMapper;
import com.smt.meeting.mapper.TranscriptSegmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 腾讯 ASR 状态机：发起/幂等、提交、成功落库、失败重试。
 */
class MinutesAsrServiceTest {

    private static final long MEETING_ID = 42L;
    private static final long USER_ID = 7L;
    private static final long MINUTES_ID = 10L;
    private static final long FILE_ID = 9L;

    private MeetingMinutesMapper minutesMapper;
    private MinutesSpeakerMapper speakerMapper;
    private TranscriptSegmentMapper segmentMapper;
    private FileObjectMapper fileObjectMapper;
    private MinutesPermissionService permissionService;
    private ObjectStorage storage;
    private AsrClient asrClient;
    private AsrProperties asrProperties;
    private TransactionTemplate transactionTemplate;
    private RedissonClient redisson;
    private RLock lock;
    private MinutesAsrService service;

    @BeforeEach
    void setUp() throws Exception {
        minutesMapper = mock(MeetingMinutesMapper.class);
        speakerMapper = mock(MinutesSpeakerMapper.class);
        segmentMapper = mock(TranscriptSegmentMapper.class);
        fileObjectMapper = mock(FileObjectMapper.class);
        permissionService = mock(MinutesPermissionService.class);
        storage = mock(ObjectStorage.class);
        asrClient = mock(AsrClient.class);
        asrProperties = new AsrProperties();
        asrProperties.setProvider("mock");
        asrProperties.setMockSegments(2);
        transactionTemplate = mock(TransactionTemplate.class);
        redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);

        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());

        service = new MinutesAsrService(
                minutesMapper, speakerMapper, segmentMapper, fileObjectMapper,
                permissionService, storage, asrClient, asrProperties,
                transactionTemplate, redisson);
    }

    @Test
    void requestCreatesMinutesAndStartsAsr() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting());
        when(fileObjectMapper.selectById(FILE_ID)).thenReturn(file());
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(null);
        doAnswer(invocation -> {
            MeetingMinutes minutes = invocation.getArgument(0);
            minutes.setId(MINUTES_ID);
            return 1;
        }).when(minutesMapper).insert(any(MeetingMinutes.class));

        MinutesTranscribeDTO dto = new MinutesTranscribeDTO();
        dto.setFileId(FILE_ID);

        assertThat(service.request(MEETING_ID, dto, USER_ID)).isEqualTo(MINUTES_ID);
        verify(minutesMapper).insert(any(MeetingMinutes.class));
        verify(minutesMapper).startAsr(eq(MINUTES_ID), eq(FILE_ID), eq(0L), any());
        verify(lock).unlock();
    }

    @Test
    void requestIsIdempotentForSamePendingFile() {
        MeetingMinutes current = minutes();
        current.setAsrStatus(10);
        current.setAudioFileId(FILE_ID);
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting());
        when(fileObjectMapper.selectById(FILE_ID)).thenReturn(file());
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(current);

        MinutesTranscribeDTO dto = new MinutesTranscribeDTO();
        dto.setFileId(FILE_ID);

        assertThat(service.request(MEETING_ID, dto, USER_ID)).isEqualTo(MINUTES_ID);
        verify(minutesMapper, never()).startAsr(anyLong(), anyLong(), anyLong(), any());
        verify(minutesMapper, never()).insert(any(MeetingMinutes.class));
    }

    @Test
    void processSubmitsPendingTask() {
        MeetingMinutes minutes = minutes();
        minutes.setAsrStatus(10);
        minutes.setAsrStartedAt(LocalDateTime.of(2026, 10, 19, 12, 0));
        when(minutesMapper.selectById(MINUTES_ID)).thenReturn(minutes);
        when(fileObjectMapper.selectById(FILE_ID)).thenReturn(file());
        when(storage.presignGet(anyString(), any())).thenReturn("https://cos.example/a.m4a");
        when(asrClient.submit(anyString())).thenReturn("task-1");
        when(minutesMapper.markAsrSubmitted(eq(MINUTES_ID), eq("task-1"), any()))
                .thenReturn(1);

        service.process(MINUTES_ID);

        verify(asrClient).submit("https://cos.example/a.m4a");
        verify(minutesMapper).markAsrSubmitted(eq(MINUTES_ID), eq("task-1"), any());
    }

    @Test
    void processPersistsSuccessfulResult() {
        MeetingMinutes minutes = minutes();
        minutes.setAsrStatus(20);
        minutes.setAsrTaskId("task-1");
        minutes.setAsrStartedAt(LocalDateTime.of(2026, 10, 19, 12, 0));
        when(minutesMapper.selectById(MINUTES_ID)).thenReturn(minutes);
        when(asrClient.query("task-1")).thenReturn(new AsrClient.AsrQueryResult(
                AsrClient.Status.SUCCESS,
                List.of(new AsrClient.Sentence("你好，", 0, 500, "0"),
                        new AsrClient.Sentence("世界。", 500, 1000, "1")),
                1000L, null));
        when(minutesMapper.selectByMeetingIdForUpdate(MEETING_ID)).thenReturn(minutes);
        when(minutesMapper.markAsrSuccess(eq(MINUTES_ID), eq(1000L), any(),
                eq(minutes.getAsrStartedAt()))).thenReturn(1);

        service.process(MINUTES_ID);

        verify(speakerMapper).batchInsert(any());
        verify(segmentMapper).batchInsert(any());
        verify(minutesMapper).markAsrSuccess(eq(MINUTES_ID), eq(1000L), any(),
                eq(minutes.getAsrStartedAt()));
    }

    @Test
    void processRetriesFailedQuery() {
        MeetingMinutes minutes = minutes();
        minutes.setAsrStatus(20);
        minutes.setAsrTaskId("task-1");
        minutes.setAsrAttempts(0);
        minutes.setAsrStartedAt(LocalDateTime.of(2026, 10, 19, 12, 0));
        when(minutesMapper.selectById(MINUTES_ID)).thenReturn(minutes);
        when(asrClient.query("task-1")).thenReturn(AsrClient.AsrQueryResult.failed("失败"));

        service.process(MINUTES_ID);

        verify(minutesMapper).markAsrRetry(eq(MINUTES_ID), eq("失败"), any(), eq(1),
                eq(minutes.getAsrStartedAt()));
    }

    private Meeting meeting() {
        Meeting meeting = new Meeting();
        meeting.setId(MEETING_ID);
        meeting.setRoomId(1L);
        meeting.setStatus(40);
        return meeting;
    }

    private MeetingMinutes minutes() {
        MeetingMinutes minutes = new MeetingMinutes();
        minutes.setId(MINUTES_ID);
        minutes.setMeetingId(MEETING_ID);
        minutes.setAudioFileId(FILE_ID);
        minutes.setAsrAttempts(0);
        minutes.setAiStatus(0);
        return minutes;
    }

    private FileObject file() {
        FileObject file = new FileObject();
        file.setId(FILE_ID);
        file.setBizType("MINUTES_AUDIO");
        file.setBizId(MEETING_ID);
        file.setObjectKey("minutes/42/a.m4a");
        file.setStatus(FileObjectStatus.NORMAL);
        return file;
    }
}
