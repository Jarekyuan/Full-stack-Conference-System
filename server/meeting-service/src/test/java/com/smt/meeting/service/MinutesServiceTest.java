package com.smt.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.config.MinutesProperties;
import com.smt.meeting.dto.MinutesImportDTO;
import com.smt.meeting.dto.SpeakerUpdateDTO;
import com.smt.meeting.entity.FileObject;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingMinutes;
import com.smt.meeting.entity.MinutesSpeaker;
import com.smt.meeting.entity.TranscriptSegment;
import com.smt.meeting.enums.FileObjectStatus;
import com.smt.meeting.enums.MinutesAiStatus;
import com.smt.meeting.mapper.FileObjectMapper;
import com.smt.meeting.mapper.MeetingMinutesMapper;
import com.smt.meeting.mapper.MinutesSpeakerMapper;
import com.smt.meeting.mapper.TranscriptSegmentMapper;
import com.smt.meeting.vo.MinutesSpeakerVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 8：纪要主服务。
 *
 * <p>重点钉住：无纪要时详情返回 null；导入前置校验（会议状态/文件归属/24h 上限）；
 * 首次导入与覆盖导入的写路径；锁内文件复查；旧音频清理与事务提交顺序；
 * 发言人重命名；预签名 URL；导入锁的获取/失败/中断/异常释放。</p>
 */
class MinutesServiceTest {

    private static final Long MEETING_ID = 42L;
    private static final Long USER_ID = 4L;
    private static final long MAX_DURATION_MS = 86_400_000L;

    private MeetingMinutesMapper minutesMapper;
    private MinutesSpeakerMapper speakerMapper;
    private TranscriptSegmentMapper segmentMapper;
    private FileObjectMapper fileObjectMapper;
    private MinutesPermissionService permissionService;
    private MinutesInsightsService insightsService;
    private ObjectStorage storage;
    private TransactionTemplate transactionTemplate;
    private RedissonClient redisson;
    private RLock lock;
    private MinutesService service;

    @BeforeEach
    void setUp() throws Exception {
        minutesMapper = mock(MeetingMinutesMapper.class);
        speakerMapper = mock(MinutesSpeakerMapper.class);
        segmentMapper = mock(TranscriptSegmentMapper.class);
        fileObjectMapper = mock(FileObjectMapper.class);
        permissionService = mock(MinutesPermissionService.class);
        insightsService = mock(MinutesInsightsService.class);
        storage = mock(ObjectStorage.class);
        transactionTemplate = mock(TransactionTemplate.class);
        redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);

        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        // 让 mock 的 TransactionTemplate 真正执行回调，同时保留事务语义的验证点
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());

        service = newService(transactionTemplate);
    }

    private MinutesService newService(TransactionTemplate template) {
        MinutesProperties properties = new MinutesProperties();
        return new MinutesService(minutesMapper, speakerMapper, segmentMapper,
                fileObjectMapper, permissionService, insightsService,
                MinutesSummaryParser.withObjectMapper(new ObjectMapper()),
                new TranscriptParser(properties), properties, storage, template, redisson);
    }

    // ---------- 夹具 ----------

    private Meeting meeting(int status) {
        Meeting meeting = new Meeting();
        meeting.setId(MEETING_ID);
        meeting.setRoomId(1L);
        meeting.setOrganiserId(USER_ID);
        meeting.setStatus(status);
        return meeting;
    }

    private FileObject audioFile(Long id) {
        FileObject file = new FileObject();
        file.setId(id);
        file.setBizType("MINUTES_AUDIO");
        file.setBizId(MEETING_ID);
        file.setObjectKey("minutes/42/x.m4a");
        file.setStatus(FileObjectStatus.NORMAL);
        file.setOriginalName("x.m4a");
        file.setSizeBytes(100L);
        return file;
    }

    private MinutesImportDTO importDTO() {
        MinutesImportDTO dto = new MinutesImportDTO();
        dto.setFileId(9L);
        dto.setTranscriptText("[0:1.000,0:2.000,0]  你好");
        dto.setDurationMs(2_000L);
        return dto;
    }

    private MeetingMinutes existingMinutes(Long id, Long audioFileId) {
        MeetingMinutes minutes = new MeetingMinutes();
        minutes.setId(id);
        minutes.setMeetingId(MEETING_ID);
        minutes.setAudioFileId(audioFileId);
        return minutes;
    }

    private static String transcriptWithSegments(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append("[0:1.000,0:2.000,0]  第").append(i).append("段\n");
        }
        return text.toString();
    }

    private static int codeOf(Throwable throwable) {
        return ((BusinessException) throwable).getCode();
    }

    // ---------- detail ----------

    @Test
    void detailReturnsNullWhenNoMinutes() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(null);

        assertThat(service.detail(MEETING_ID, USER_ID)).isNull();
        verify(permissionService).assertCanView(any(Meeting.class), eq(USER_ID));
    }

    // ---------- import：前置校验 ----------

    @Test
    void importRejectsBeforeMeetingEnded() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(30));

        assertThatThrownBy(() -> service.importMinutes(MEETING_ID, importDTO(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MinutesServiceTest::codeOf)
                .isEqualTo(ErrorCode.MINUTES_STATUS_NOT_ALLOWED.getCode());

        // 状态不合法时不应触碰文件、转写与导入锁
        verify(fileObjectMapper, never()).selectById(any());
        verify(redisson, never()).getLock(anyString());
    }

    @Test
    void importRejectsFileFromAnotherMeeting() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        FileObject file = audioFile(9L);
        file.setBizId(99L);
        when(fileObjectMapper.selectById(9L)).thenReturn(file);

        assertThatThrownBy(() -> service.importMinutes(MEETING_ID, importDTO(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MinutesServiceTest::codeOf)
                .isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED.getCode());

        verify(redisson, never()).getLock(anyString());
    }

    @Test
    void importRevalidatesFileInsideLock() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        FileObject normal = audioFile(9L);
        FileObject deleted = audioFile(9L);
        deleted.setStatus(FileObjectStatus.DELETED);
        // 锁前校验通过；拿到锁后（并发重新导入已把文件标记删除）复查失败
        when(fileObjectMapper.selectById(9L)).thenReturn(normal, deleted);

        assertThatThrownBy(() -> service.importMinutes(MEETING_ID, importDTO(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MinutesServiceTest::codeOf)
                .isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED.getCode());

        verify(fileObjectMapper, times(2)).selectById(9L);
        verify(minutesMapper, never()).insert(any(MeetingMinutes.class));
        verify(lock).unlock();
    }

    @Test
    void importRejectsDurationBeyond24Hours() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        when(fileObjectMapper.selectById(9L)).thenReturn(audioFile(9L));
        MinutesImportDTO dto = importDTO();
        dto.setDurationMs(MAX_DURATION_MS + 1);

        assertThatThrownBy(() -> service.importMinutes(MEETING_ID, dto, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MinutesServiceTest::codeOf)
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());

        verify(redisson, never()).getLock(anyString());
    }

    // ---------- import：首次导入 ----------

    @Test
    void importCreatesMinutesAndSegments() throws Exception {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        when(fileObjectMapper.selectById(9L)).thenReturn(audioFile(9L));
        // 首次导入：事务内 selectByMeetingId 不存在 → 直接 INSERT，绝不对缺失行加 FOR UPDATE
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(null);
        doAnswer(invocation -> {
            MeetingMinutes minutes = invocation.getArgument(0);
            minutes.setId(99L);
            return 1;
        }).when(minutesMapper).insert(any(MeetingMinutes.class));

        Long minutesId = service.importMinutes(MEETING_ID, importDTO(), USER_ID);

        assertThat(minutesId).isEqualTo(99L);
        // 锁包住事务，且 finally 释放
        verify(redisson).getLock("lock:minutes:import:42");
        verify(lock).tryLock(3, TimeUnit.SECONDS);
        verify(lock).unlock();
        verify(minutesMapper, never()).selectByMeetingIdForUpdate(MEETING_ID);

        ArgumentCaptor<MeetingMinutes> minutesCaptor = ArgumentCaptor.forClass(MeetingMinutes.class);
        verify(minutesMapper).insert(minutesCaptor.capture());
        MeetingMinutes saved = minutesCaptor.getValue();
        assertThat(saved.getMeetingId()).isEqualTo(MEETING_ID);
        assertThat(saved.getRoomId()).isEqualTo(1L);
        assertThat(saved.getAudioFileId()).isEqualTo(9L);
        assertThat(saved.getTranscriptSource()).isEqualTo("TENCENT_ASR");
        assertThat(saved.getDurationMs()).isEqualTo(2_000L);
        assertThat(saved.getAiStatus()).isEqualTo(MinutesAiStatus.NOT_GENERATED.getCode());
        assertThat(saved.getCreatedBy()).isEqualTo(USER_ID);

        verify(speakerMapper).deleteByMinutesId(99L);
        verify(segmentMapper).deleteByMinutesId(99L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MinutesSpeaker>> speakerCaptor = ArgumentCaptor.forClass(List.class);
        verify(speakerMapper).batchInsert(speakerCaptor.capture());
        assertThat(speakerCaptor.getValue()).singleElement().satisfies(speaker -> {
            assertThat(speaker.getMinutesId()).isEqualTo(99L);
            assertThat(speaker.getSpeakerKey()).isEqualTo("0");
            assertThat(speaker.getDisplayName()).isEqualTo("说话人 1");
            assertThat(speaker.getColor()).isEqualTo("#7D8CFF");
        });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TranscriptSegment>> segmentCaptor = ArgumentCaptor.forClass(List.class);
        verify(segmentMapper).batchInsert(segmentCaptor.capture());
        assertThat(segmentCaptor.getValue()).singleElement().satisfies(segment -> {
            assertThat(segment.getMinutesId()).isEqualTo(99L);
            assertThat(segment.getSeq()).isZero();
            assertThat(segment.getSpeakerKey()).isEqualTo("0");
            assertThat(segment.getStartMs()).isEqualTo(1_000L);
            assertThat(segment.getEndMs()).isEqualTo(2_000L);
            assertThat(segment.getText()).isEqualTo("你好");
        });

        verify(minutesMapper).resetAi(99L);
        verify(minutesMapper, never()).updateImport(any(), any(), anyLong(), anyString());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void importBatchesSegmentsBy500() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        when(fileObjectMapper.selectById(9L)).thenReturn(audioFile(9L));
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(null);
        doAnswer(invocation -> {
            MeetingMinutes minutes = invocation.getArgument(0);
            minutes.setId(99L);
            return 1;
        }).when(minutesMapper).insert(any(MeetingMinutes.class));
        MinutesImportDTO dto = importDTO();
        dto.setTranscriptText(transcriptWithSegments(501));

        service.importMinutes(MEETING_ID, dto, USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TranscriptSegment>> segmentCaptor = ArgumentCaptor.forClass(List.class);
        verify(segmentMapper, times(2)).batchInsert(segmentCaptor.capture());
        assertThat(segmentCaptor.getAllValues()).hasSize(2);
        assertThat(segmentCaptor.getAllValues().get(0)).hasSize(500);
        assertThat(segmentCaptor.getAllValues().get(1)).hasSize(1);
        verify(speakerMapper).batchInsert(any());
        verify(minutesMapper).resetAi(99L);
    }

    // ---------- import：覆盖导入 ----------

    @Test
    void importExistingUpdatesAndDeletesOldFile() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        when(fileObjectMapper.selectById(9L)).thenReturn(audioFile(9L));

        MeetingMinutes existing = existingMinutes(99L, 8L);
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(existing);
        when(minutesMapper.selectByMeetingIdForUpdate(MEETING_ID)).thenReturn(existing);

        FileObject oldFile = audioFile(8L);
        oldFile.setObjectKey("minutes/42/old.m4a");
        when(fileObjectMapper.selectById(8L)).thenReturn(oldFile);

        Long minutesId = service.importMinutes(MEETING_ID, importDTO(), USER_ID);

        assertThat(minutesId).isEqualTo(99L);
        verify(minutesMapper).selectByMeetingIdForUpdate(MEETING_ID);
        verify(minutesMapper).updateImport(99L, 9L, 2_000L, "TENCENT_ASR");
        verify(minutesMapper, never()).insert(any(MeetingMinutes.class));
        verify(speakerMapper).deleteByMinutesId(99L);
        verify(segmentMapper).deleteByMinutesId(99L);
        verify(minutesMapper).resetAi(99L);
        // 旧音频在事务提交后清理：元数据置删除 + 对象删除
        verify(fileObjectMapper).markDeleted(8L);
        verify(storage).delete("minutes/42/old.m4a");
        verify(lock).unlock();
    }

    @Test
    void importSkipsCleanupWhenAudioFileUnchanged() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        when(fileObjectMapper.selectById(9L)).thenReturn(audioFile(9L));

        MeetingMinutes existing = existingMinutes(99L, 9L);
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(existing);
        when(minutesMapper.selectByMeetingIdForUpdate(MEETING_ID)).thenReturn(existing);

        assertThat(service.importMinutes(MEETING_ID, importDTO(), USER_ID)).isEqualTo(99L);

        verify(minutesMapper).updateImport(99L, 9L, 2_000L, "TENCENT_ASR");
        verify(fileObjectMapper, never()).markDeleted(any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void importCommitsBeforeCleaningOldFile() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        when(fileObjectMapper.selectById(9L)).thenReturn(audioFile(9L));

        MeetingMinutes existing = existingMinutes(99L, 8L);
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(existing);
        when(minutesMapper.selectByMeetingIdForUpdate(MEETING_ID)).thenReturn(existing);
        FileObject oldFile = audioFile(8L);
        oldFile.setObjectKey("minutes/42/old.m4a");
        when(fileObjectMapper.selectById(8L)).thenReturn(oldFile);

        // 真实 TransactionTemplate + mock 事务管理器：验证 commit 与旧对象清理的先后顺序
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        MinutesService realTransactionService = newService(new TransactionTemplate(txManager));

        assertThat(realTransactionService.importMinutes(MEETING_ID, importDTO(), USER_ID))
                .isEqualTo(99L);

        InOrder inOrder = inOrder(txManager, fileObjectMapper, storage);
        inOrder.verify(txManager).commit(any());
        inOrder.verify(fileObjectMapper).markDeleted(8L);
        inOrder.verify(storage).delete("minutes/42/old.m4a");
    }

    @Test
    void importTransactionFailureSkipsCleanupAndUnlocks() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        when(fileObjectMapper.selectById(9L)).thenReturn(audioFile(9L));
        when(minutesMapper.selectByMeetingId(MEETING_ID))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.importMinutes(MEETING_ID, importDTO(), USER_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        verify(fileObjectMapper, never()).markDeleted(any());
        verify(storage, never()).delete(anyString());
        verify(lock).unlock();
    }

    // ---------- import：锁 ----------

    @Test
    void importLockNotAcquiredReturnsSystemBusy() throws Exception {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        when(fileObjectMapper.selectById(9L)).thenReturn(audioFile(9L));
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(false);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        assertThatThrownBy(() -> service.importMinutes(MEETING_ID, importDTO(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MinutesServiceTest::codeOf)
                .isEqualTo(ErrorCode.SYSTEM_BUSY.getCode());

        verify(transactionTemplate, never()).execute(any());
        verify(minutesMapper, never()).insert(any(MeetingMinutes.class));
        verify(lock, never()).unlock();
    }

    @Test
    void importInterruptedReturnsSystemBusyAndRestoresFlag() throws Exception {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        when(fileObjectMapper.selectById(9L)).thenReturn(audioFile(9L));
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        when(lock.tryLock(3, TimeUnit.SECONDS))
                .thenThrow(new InterruptedException("interrupted"));

        try {
            assertThatThrownBy(() -> service.importMinutes(MEETING_ID, importDTO(), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(MinutesServiceTest::codeOf)
                    .isEqualTo(ErrorCode.SYSTEM_BUSY.getCode());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // 清除中断标志，避免影响后续用例
        }

        verify(transactionTemplate, never()).execute(any());
        verify(lock, never()).unlock();
    }

    @Test
    void importLockFailureAfterAcquisitionStillUnlocks() throws Exception {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        when(fileObjectMapper.selectById(9L)).thenReturn(audioFile(9L));
        // isHeldByCurrentThread=true（setUp）模拟"服务端已加锁后 Redis 响应超时"
        when(lock.tryLock(3, TimeUnit.SECONDS))
                .thenThrow(new RuntimeException("redis timeout"));

        assertThatThrownBy(() -> service.importMinutes(MEETING_ID, importDTO(), USER_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("redis timeout");

        verify(lock).unlock();
        verify(transactionTemplate, never()).execute(any());
    }

    // ---------- renameSpeaker ----------

    @Test
    void renameSpeakerReturnsUpdatedValue() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        MeetingMinutes minutes = new MeetingMinutes();
        minutes.setId(99L);
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(minutes);
        when(speakerMapper.updateDisplayName(99L, "0", "张三", "#7D8CFF")).thenReturn(1);

        SpeakerUpdateDTO dto = new SpeakerUpdateDTO();
        dto.setDisplayName("张三");
        dto.setColor("#7D8CFF");

        MinutesSpeakerVO result = service.renameSpeaker(MEETING_ID, "0", dto, USER_ID);

        assertThat(result.getSpeakerKey()).isEqualTo("0");
        assertThat(result.getDisplayName()).isEqualTo("张三");
        assertThat(result.getColor()).isEqualTo("#7D8CFF");
        verify(speakerMapper).updateDisplayName(99L, "0", "张三", "#7D8CFF");
        verify(permissionService).assertCanManage(any(Meeting.class), eq(USER_ID));
    }

    @Test
    void renameSpeakerUnknownKeyReturnsNotFound() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        MeetingMinutes minutes = new MeetingMinutes();
        minutes.setId(99L);
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(minutes);
        when(speakerMapper.updateDisplayName(99L, "missing", "张三", "#7D8CFF")).thenReturn(0);

        SpeakerUpdateDTO dto = new SpeakerUpdateDTO();
        dto.setDisplayName("张三");
        dto.setColor("#7D8CFF");

        assertThatThrownBy(() -> service.renameSpeaker(MEETING_ID, "missing", dto, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MinutesServiceTest::codeOf)
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    // ---------- audioUrl ----------

    @Test
    void audioUrlUsesPresignedGet() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        MeetingMinutes minutes = existingMinutes(99L, 9L);
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(minutes);
        when(fileObjectMapper.selectById(9L)).thenReturn(audioFile(9L));
        when(storage.presignGet(eq("minutes/42/x.m4a"), any(Duration.class)))
                .thenReturn("https://signed");

        LocalDateTime before = LocalDateTime.now();
        var result = service.audioUrl(MEETING_ID, USER_ID);

        assertThat(result.getUrl()).isEqualTo("https://signed");
        assertThat(result.getExpiresAt()).isAfterOrEqualTo(before.plusMinutes(60));
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(storage).presignGet(eq("minutes/42/x.m4a"), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofMinutes(60));
        verify(permissionService).assertCanView(any(Meeting.class), eq(USER_ID));
    }

    @Test
    void audioUrlRejectsDeletedFile() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
        MeetingMinutes minutes = existingMinutes(99L, 9L);
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(minutes);
        FileObject deleted = audioFile(9L);
        deleted.setStatus(FileObjectStatus.DELETED);
        when(fileObjectMapper.selectById(9L)).thenReturn(deleted);

        assertThatThrownBy(() -> service.audioUrl(MEETING_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MinutesServiceTest::codeOf)
                .isEqualTo(ErrorCode.MINUTES_NOT_FOUND.getCode());

        verify(storage, never()).presignGet(anyString(), any());
    }
}
