package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.config.MinutesProperties;
import com.smt.meeting.dto.MultipartCompleteDTO;
import com.smt.meeting.dto.MultipartInitDTO;
import com.smt.meeting.entity.FileObject;
import com.smt.meeting.entity.FileUploadSession;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.enums.FileObjectStatus;
import com.smt.meeting.enums.MeetingStatusEnum;
import com.smt.meeting.enums.UploadSessionStatus;
import com.smt.meeting.mapper.FileObjectMapper;
import com.smt.meeting.mapper.FileUploadSessionMapper;
import com.smt.meeting.vo.FileCompleteVO;
import com.smt.meeting.vo.MultipartInitVO;
import com.smt.meeting.vo.MultipartPartVO;
import com.smt.meeting.vo.MultipartSessionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 7：分片上传业务层。
 *
 * <p>重点钉住四条容易写反的契约：</p>
 * <ul>
 *   <li><b>已完成(10) 是幂等终点</b>——session() 不得调 storage.listParts（存储层对完成态抛 70006），
 *       重复 complete 返回第一次落库的文件；而 partUrls 对完成态必须拒绝。</li>
 *   <li><b>归属校验先于一切</b>——别人的 uploadId 与"不存在"返回同一个 70006。</li>
 *   <li><b>大小预检必须在 completeMultipart 之前</b>——complete 会消费 uploadId，事后无法重试。</li>
 *   <li><b>DB 落库是短事务 + 行锁 CAS</b>——markCompleted 0 行必须回滚并清理已合并对象。</li>
 * </ul>
 */
class MultipartUploadServiceTest {

    private static final Long USER_ID = 4L;
    private static final Long OTHER_USER_ID = 5L;
    private static final Long MEETING_ID = 42L;
    private static final String UPLOAD_ID = "upload-1";
    private static final String OBJECT_KEY = "minutes/42/202610/x.m4a";

    private ObjectStorage storage;
    private FileObjectMapper fileObjectMapper;
    private FileUploadSessionMapper sessionMapper;
    private MinutesPermissionService permissionService;
    private PlatformTransactionManager txManager;
    private MinutesProperties properties;
    private MultipartUploadService service;

    @BeforeEach
    void setUp() {
        storage = mock(ObjectStorage.class);
        fileObjectMapper = mock(FileObjectMapper.class);
        sessionMapper = mock(FileUploadSessionMapper.class);
        permissionService = mock(MinutesPermissionService.class);
        properties = new MinutesProperties();
        // 真实 TransactionTemplate + mock 事务管理器：既能执行回调，也能断言 commit/rollback
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new MultipartUploadService(storage, fileObjectMapper, sessionMapper,
                permissionService, properties, new TransactionTemplate(txManager));
    }

    // ---------- 夹具 ----------

    private Meeting meeting(int status) {
        Meeting meeting = new Meeting();
        meeting.setId(MEETING_ID);
        meeting.setOrganiserId(USER_ID);
        meeting.setStatus(status);
        return meeting;
    }

    private MultipartInitDTO initDTO() {
        MultipartInitDTO dto = new MultipartInitDTO();
        dto.setBizType("MINUTES_AUDIO");
        dto.setBizId(MEETING_ID);
        dto.setFileName("meeting.m4a");
        dto.setFileSize(11L);
        dto.setContentType("audio/mp4");
        return dto;
    }

    private FileUploadSession session(int status, LocalDateTime expiresAt,
                                      int totalParts, Long fileId) {
        FileUploadSession session = new FileUploadSession();
        session.setUploadId(UPLOAD_ID);
        session.setBizType("MINUTES_AUDIO");
        session.setBizId(MEETING_ID);
        session.setObjectKey(OBJECT_KEY);
        session.setOriginalName("meeting.m4a");
        session.setSizeBytes(11L);
        session.setContentType("audio/mp4");
        session.setChunkSize(5);
        session.setTotalParts(totalParts);
        session.setStatus(status);
        session.setFileId(fileId);
        session.setCreatedBy(USER_ID);
        session.setExpiresAt(expiresAt);
        return session;
    }

    private MultipartCompleteDTO completeDTO(Integer... partNumbers) {
        MultipartCompleteDTO dto = new MultipartCompleteDTO();
        List<MultipartCompleteDTO.Part> parts = new ArrayList<>();
        for (Integer partNumber : partNumbers) {
            MultipartCompleteDTO.Part part = new MultipartCompleteDTO.Part();
            part.setPartNumber(partNumber);
            part.setEtag("etag-" + partNumber);
            parts.add(part);
        }
        dto.setParts(parts);
        return dto;
    }

    private MultipartCompleteDTO completeDTOWithNullElement() {
        MultipartCompleteDTO.Part part = new MultipartCompleteDTO.Part();
        part.setPartNumber(1);
        part.setEtag("etag-1");
        List<MultipartCompleteDTO.Part> parts = new ArrayList<>();
        parts.add(part);
        parts.add(null);
        MultipartCompleteDTO dto = new MultipartCompleteDTO();
        dto.setParts(parts);
        return dto;
    }

    private FileObject fileObject(Long id) {
        FileObject file = new FileObject();
        file.setId(id);
        file.setObjectKey(OBJECT_KEY);
        file.setSizeBytes(11L);
        return file;
    }

    private static int codeOf(Throwable throwable) {
        return ((BusinessException) throwable).getCode();
    }

    /** 预检通过的已上传分片（总大小 = 声明大小 11） */
    private void stubMatchingParts() {
        when(storage.listParts(OBJECT_KEY, UPLOAD_ID)).thenReturn(List.of(
                new ObjectStorage.UploadedPart(1, "etag-1", 5),
                new ObjectStorage.UploadedPart(2, "etag-2", 6)));
    }

    /** complete 成功路径的完整桩：预检通过、合并成功、行锁会话仍为进行中、插入回填 id */
    private void stubSuccessfulPersistence(FileUploadSession locked, Long fileId) {
        when(sessionMapper.selectByUploadIdForUpdate(UPLOAD_ID)).thenReturn(locked);
        when(fileObjectMapper.insert(any(FileObject.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, FileObject.class).setId(fileId);
            return 1;
        });
        when(sessionMapper.markCompleted(UPLOAD_ID, fileId)).thenReturn(1);
    }

    // ---------- init ----------

    @Test
    void initShouldRejectWhenMeetingNotEnded() {
        when(permissionService.requireMeeting(MEETING_ID))
                .thenReturn(meeting(MeetingStatusEnum.ONGOING.getCode()));

        assertThatThrownBy(() -> service.init(initDTO(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.MINUTES_STATUS_NOT_ALLOWED.getCode());
        verify(storage, never()).initMultipart(anyString(), anyString());
        verify(sessionMapper, never()).insert(any(FileUploadSession.class));
    }

    @Test
    void initShouldRejectUnsupportedExtension() {
        when(permissionService.requireMeeting(MEETING_ID))
                .thenReturn(meeting(MeetingStatusEnum.FINISHED.getCode()));
        MultipartInitDTO dto = initDTO();
        dto.setFileName("meeting.exe");
        dto.setContentType("audio/mpeg");

        assertThatThrownBy(() -> service.init(dto, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED.getCode());
        verify(storage, never()).initMultipart(anyString(), anyString());
    }

    @Test
    void initShouldRejectUnsupportedContentTypeWithValidExtension() {
        when(permissionService.requireMeeting(MEETING_ID))
                .thenReturn(meeting(MeetingStatusEnum.FINISHED.getCode()));
        MultipartInitDTO dto = initDTO();
        dto.setFileName("meeting.m4a");
        dto.setContentType("video/mp4");

        assertThatThrownBy(() -> service.init(dto, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED.getCode());
        verify(storage, never()).initMultipart(anyString(), anyString());
    }

    @Test
    void initShouldRejectOversizedFile() {
        when(permissionService.requireMeeting(MEETING_ID))
                .thenReturn(meeting(MeetingStatusEnum.FINISHED.getCode()));
        MultipartInitDTO dto = initDTO();
        dto.setFileSize(properties.getUpload().getMaxFileSizeBytes() + 1);

        assertThatThrownBy(() -> service.init(dto, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.FILE_TOO_LARGE.getCode());
        verify(storage, never()).initMultipart(anyString(), anyString());
    }

    @Test
    void initShouldPropagatePermissionDenial() {
        when(permissionService.requireMeeting(MEETING_ID))
                .thenReturn(meeting(MeetingStatusEnum.FINISHED.getCode()));
        doThrow(new BusinessException(ErrorCode.NO_PERMISSION))
                .when(permissionService).assertCanManage(any(Meeting.class), eq(USER_ID));

        assertThatThrownBy(() -> service.init(initDTO(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.NO_PERMISSION.getCode());
        verify(storage, never()).initMultipart(anyString(), anyString());
        verify(sessionMapper, never()).insert(any(FileUploadSession.class));
    }

    @Test
    void initShouldCreateSession() {
        when(permissionService.requireMeeting(MEETING_ID))
                .thenReturn(meeting(MeetingStatusEnum.FINISHED.getCode()));
        when(storage.initMultipart(anyString(), anyString()))
                .thenReturn(new ObjectStorage.MultipartInit(UPLOAD_ID));

        MultipartInitVO result = service.init(initDTO(), USER_ID);

        assertThat(result.getUploadId()).isEqualTo(UPLOAD_ID);
        assertThat(result.getTotalParts()).isEqualTo(1);
        assertThat(result.getChunkSize()).isEqualTo(properties.getUpload().getChunkSizeBytes());
        assertThat(result.getObjectKey()).startsWith("minutes/42/").endsWith(".m4a");

        ArgumentCaptor<FileUploadSession> captor =
                ArgumentCaptor.forClass(FileUploadSession.class);
        verify(sessionMapper).insert(captor.capture());
        FileUploadSession saved = captor.getValue();
        assertThat(saved.getUploadId()).isEqualTo(UPLOAD_ID);
        assertThat(saved.getStatus()).isEqualTo(UploadSessionStatus.IN_PROGRESS);
        assertThat(saved.getTotalParts()).isEqualTo(1);
        assertThat(saved.getChunkSize()).isEqualTo(properties.getUpload().getChunkSizeBytes());
        assertThat(saved.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void initShouldAllowExactly1000Parts() {
        when(permissionService.requireMeeting(MEETING_ID))
                .thenReturn(meeting(MeetingStatusEnum.FINISHED.getCode()));
        properties.getUpload().setChunkSizeBytes(1);
        when(storage.initMultipart(anyString(), anyString()))
                .thenReturn(new ObjectStorage.MultipartInit(UPLOAD_ID));
        MultipartInitDTO dto = initDTO();
        dto.setFileSize(1000L);

        MultipartInitVO result = service.init(dto, USER_ID);

        // COS ListParts 单页上限 1000：恰好 1000 片仍可被预检完整枚举
        assertThat(result.getTotalParts()).isEqualTo(1000);
        verify(sessionMapper).insert(any(FileUploadSession.class));
    }

    @Test
    void initShouldRejectMoreThan1000Parts() {
        when(permissionService.requireMeeting(MEETING_ID))
                .thenReturn(meeting(MeetingStatusEnum.FINISHED.getCode()));
        properties.getUpload().setChunkSizeBytes(1);
        MultipartInitDTO dto = initDTO();
        dto.setFileSize(1001L);

        assertThatThrownBy(() -> service.init(dto, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        verify(storage, never()).initMultipart(anyString(), anyString());
    }

    @Test
    void initShouldRejectHugePartCountWithoutIntOverflow() {
        when(permissionService.requireMeeting(MEETING_ID))
                .thenReturn(meeting(MeetingStatusEnum.FINISHED.getCode()));
        properties.getUpload().setChunkSizeBytes(1);
        MultipartInitDTO dto = initDTO();
        // 恰好是允许的最大文件：totalParts = 2GiB 个分片。若先 (int) 强转会溢出成负数绕过上限
        dto.setFileSize(properties.getUpload().getMaxFileSizeBytes());

        assertThatThrownBy(() -> service.init(dto, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        verify(storage, never()).initMultipart(anyString(), anyString());
    }

    @Test
    void initShouldAbortStorageWhenSessionInsertFails() {
        when(permissionService.requireMeeting(MEETING_ID))
                .thenReturn(meeting(MeetingStatusEnum.FINISHED.getCode()));
        when(storage.initMultipart(anyString(), anyString()))
                .thenReturn(new ObjectStorage.MultipartInit(UPLOAD_ID));
        when(sessionMapper.insert(any(FileUploadSession.class)))
                .thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> service.init(initDTO(), USER_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB down");
        // 数据库没有会话行 → 存储侧 upload 必须被主动清理，否则永远没人能 abort 它
        verify(storage).abortMultipart(anyString(), eq(UPLOAD_ID));
    }

    // ---------- session / 归属 ----------

    @Test
    void sessionShouldRejectAnotherUsersUpload() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.IN_PROGRESS,
                        LocalDateTime.now().plusHours(1), 3, null));

        assertThatThrownBy(() -> service.session(UPLOAD_ID, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.UPLOAD_SESSION_NOT_FOUND.getCode());
        verify(storage, never()).listParts(anyString(), anyString());
    }

    @Test
    void sessionShouldListMissingParts() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.IN_PROGRESS,
                        LocalDateTime.now().plusHours(1), 3, null));
        when(storage.listParts(OBJECT_KEY, UPLOAD_ID))
                .thenReturn(List.of(new ObjectStorage.UploadedPart(1, "etag-1", 5)));

        MultipartSessionVO result = service.session(UPLOAD_ID, USER_ID);

        assertThat(result.getStatus()).isEqualTo(UploadSessionStatus.IN_PROGRESS);
        assertThat(result.getUploadedParts()).hasSize(1);
        assertThat(result.getUploadedParts().get(0).getPartNumber()).isEqualTo(1);
        assertThat(result.getMissingPartNumbers()).containsExactly(2, 3);
    }

    @Test
    void completedSessionShouldShortCircuitAndNotCallListParts() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.COMPLETED,
                        LocalDateTime.now().plusHours(1), 3, 9L));

        MultipartSessionVO result = service.session(UPLOAD_ID, USER_ID);

        assertThat(result.getStatus()).isEqualTo(UploadSessionStatus.COMPLETED);
        assertThat(result.getFileId()).isEqualTo(9L);
        assertThat(result.getUploadedParts()).isEmpty();
        assertThat(result.getMissingPartNumbers()).isEmpty();
        // 存储层对已完成会话的 listParts 抛 70006——业务层必须先短路
        verify(storage, never()).listParts(anyString(), anyString());
    }

    @Test
    void expiredInProgressSessionShouldRejectWith70006() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.IN_PROGRESS,
                        LocalDateTime.now().minusMinutes(1), 3, null));

        assertThatThrownBy(() -> service.session(UPLOAD_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.UPLOAD_SESSION_NOT_FOUND.getCode());
        verify(storage, never()).listParts(anyString(), anyString());
    }

    @Test
    void completedSessionWithPastExpiresAtShouldStillReturn() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.COMPLETED,
                        LocalDateTime.now().minusHours(1), 3, 9L));

        MultipartSessionVO result = service.session(UPLOAD_ID, USER_ID);

        // 完成是终态：expires_at 已过不影响幂等查询
        assertThat(result.getStatus()).isEqualTo(UploadSessionStatus.COMPLETED);
        assertThat(result.getFileId()).isEqualTo(9L);
        assertThat(result.getUploadedParts()).isEmpty();
        assertThat(result.getMissingPartNumbers()).isEmpty();
        verify(storage, never()).listParts(anyString(), anyString());
    }

    // ---------- partUrls ----------

    @Test
    void partUrlsShouldReturnUrlsAndDeduplicate() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.IN_PROGRESS,
                        LocalDateTime.now().plusHours(1), 3, null));
        when(storage.presignPart(eq(OBJECT_KEY), eq(UPLOAD_ID), anyInt(), any()))
                .thenAnswer(invocation -> "url-" + invocation.getArgument(2));

        List<MultipartPartVO> result = service.partUrls(UPLOAD_ID, List.of(1, 2, 2), USER_ID);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(MultipartPartVO::getPartNumber)
                .containsExactlyInAnyOrder(1, 2);
        assertThat(result.get(0).getExpiresAt()).isAfter(LocalDateTime.now());
        verify(storage, times(2)).presignPart(eq(OBJECT_KEY), eq(UPLOAD_ID), anyInt(), any());
    }

    @Test
    void partUrlsShouldRejectOutOfRangeNumbers() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.IN_PROGRESS,
                        LocalDateTime.now().plusHours(1), 3, null));

        assertThatThrownBy(() -> service.partUrls(UPLOAD_ID, List.of(0), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        assertThatThrownBy(() -> service.partUrls(UPLOAD_ID, List.of(4), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        verify(storage, never()).presignPart(anyString(), anyString(), anyInt(), any());
    }

    @Test
    void partUrlsShouldRejectMoreThan50UniqueParts() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.IN_PROGRESS,
                        LocalDateTime.now().plusHours(1), 100, null));

        List<Integer> tooMany = IntStream.rangeClosed(1, 51).boxed().toList();

        assertThatThrownBy(() -> service.partUrls(UPLOAD_ID, tooMany, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        verify(storage, never()).presignPart(anyString(), anyString(), anyInt(), any());
    }

    @Test
    void partUrlsShouldRejectCompletedSession() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.COMPLETED,
                        LocalDateTime.now().plusHours(1), 3, 9L));

        assertThatThrownBy(() -> service.partUrls(UPLOAD_ID, List.of(1), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.UPLOAD_SESSION_NOT_FOUND.getCode());
        verify(storage, never()).presignPart(anyString(), anyString(), anyInt(), any());
    }

    // ---------- complete ----------

    @Test
    void completeShouldReturnExistingFileWhenSessionAlreadyCompleted() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.COMPLETED,
                        LocalDateTime.now().plusHours(1), 3, 9L));
        when(fileObjectMapper.selectById(9L)).thenReturn(fileObject(9L));

        // 空 DTO（parts=null）：已完成分支必须在解引用前返回
        FileCompleteVO result = service.complete(UPLOAD_ID, new MultipartCompleteDTO(), USER_ID);

        assertThat(result.getFileId()).isEqualTo(9L);
        assertThat(result.getObjectKey()).isEqualTo(OBJECT_KEY);
        verify(storage, never()).completeMultipart(anyString(), anyString(), any());
        verify(fileObjectMapper, never()).insert(any(FileObject.class));
    }

    @Test
    void completeShouldReturnExistingFileWhenCompletedSessionExpired() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.COMPLETED,
                        LocalDateTime.now().minusHours(1), 3, 9L));
        when(fileObjectMapper.selectById(9L)).thenReturn(fileObject(9L));

        FileCompleteVO result = service.complete(UPLOAD_ID,
                completeDTO(1, 2, 3), USER_ID);

        assertThat(result.getFileId()).isEqualTo(9L);
        verify(storage, never()).listParts(anyString(), anyString());
        verify(storage, never()).completeMultipart(anyString(), anyString(), any());
    }

    @Test
    void completeShouldRejectMissingParts() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.IN_PROGRESS,
                        LocalDateTime.now().plusHours(1), 2, null));

        assertThatThrownBy(() -> service.complete(UPLOAD_ID, completeDTO(1), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.UPLOAD_INCOMPLETE.getCode());
        verify(storage, never()).listParts(anyString(), anyString());
        verify(storage, never()).completeMultipart(anyString(), anyString(), any());
    }

    @Test
    void completeShouldRejectEmptyNullNonContiguousAndDuplicateParts() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.IN_PROGRESS,
                        LocalDateTime.now().plusHours(1), 2, null));

        MultipartCompleteDTO empty = new MultipartCompleteDTO();
        empty.setParts(List.of());
        MultipartCompleteDTO nullList = new MultipartCompleteDTO();
        nullList.setParts(null);

        for (MultipartCompleteDTO dto : List.of(
                empty, nullList, completeDTOWithNullElement(),
                completeDTO(1, 3), completeDTO(1, 1))) {
            assertThatThrownBy(() -> service.complete(UPLOAD_ID, dto, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(MultipartUploadServiceTest::codeOf)
                    .isEqualTo(ErrorCode.UPLOAD_INCOMPLETE.getCode());
        }
        verify(storage, never()).listParts(anyString(), anyString());
        verify(storage, never()).completeMultipart(anyString(), anyString(), any());
    }

    @Test
    void completeShouldRejectSizeMismatchBeforeConsumingUpload() {
        FileUploadSession session = session(UploadSessionStatus.IN_PROGRESS,
                LocalDateTime.now().plusHours(1), 2, null);
        session.setSizeBytes(10L);
        when(sessionMapper.selectByUploadId(UPLOAD_ID)).thenReturn(session);
        when(storage.listParts(OBJECT_KEY, UPLOAD_ID)).thenReturn(List.of(
                new ObjectStorage.UploadedPart(1, "etag-1", 4),
                new ObjectStorage.UploadedPart(2, "etag-2", 4)));

        assertThatThrownBy(() -> service.complete(UPLOAD_ID, completeDTO(1, 2), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.UPLOAD_INCOMPLETE.getCode());
        // 关键：预检失败时 uploadId 还没被消费，客户端可以重传后重试
        verify(storage, never()).completeMultipart(anyString(), anyString(), any());
        verify(fileObjectMapper, never()).insert(any(FileObject.class));
    }

    @Test
    void completeShouldRecoverWhenListPartsReportsConcurrentCompletion() {
        FileUploadSession inProgress = session(UploadSessionStatus.IN_PROGRESS,
                LocalDateTime.now().plusHours(1), 2, null);
        FileUploadSession completed = session(UploadSessionStatus.COMPLETED,
                LocalDateTime.now().plusHours(1), 2, 9L);
        inProgress.setSizeBytes(11L);
        completed.setSizeBytes(11L);
        // requireSession 读进行中；listParts 抛 70006 后重读为已完成
        when(sessionMapper.selectByUploadId(UPLOAD_ID)).thenReturn(inProgress, completed);
        when(storage.listParts(OBJECT_KEY, UPLOAD_ID))
                .thenThrow(new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND));
        when(fileObjectMapper.selectById(9L)).thenReturn(fileObject(9L));

        FileCompleteVO result = service.complete(UPLOAD_ID, completeDTO(1, 2), USER_ID);

        assertThat(result.getFileId()).isEqualTo(9L);
        verify(storage, never()).completeMultipart(anyString(), anyString(), any());
        verify(fileObjectMapper, never()).insert(any(FileObject.class));
    }

    @Test
    void completeShouldCreateFileObject() {
        FileUploadSession session = session(UploadSessionStatus.IN_PROGRESS,
                LocalDateTime.now().plusHours(1), 2, null);
        FileUploadSession locked = session(UploadSessionStatus.IN_PROGRESS,
                LocalDateTime.now().plusHours(1), 2, null);
        session.setSizeBytes(11L);
        locked.setSizeBytes(11L);
        when(sessionMapper.selectByUploadId(UPLOAD_ID)).thenReturn(session);
        stubMatchingParts();
        when(storage.completeMultipart(eq(OBJECT_KEY), eq(UPLOAD_ID), any()))
                .thenReturn(new ObjectStorage.StoredObject(OBJECT_KEY, 11L, "audio/mp4"));
        stubSuccessfulPersistence(locked, 88L);

        // 乱序提交：服务端排序后再交给存储
        FileCompleteVO result = service.complete(UPLOAD_ID, completeDTO(2, 1), USER_ID);

        assertThat(result.getFileId()).isEqualTo(88L);
        assertThat(result.getObjectKey()).isEqualTo(OBJECT_KEY);
        assertThat(result.getSizeBytes()).isEqualTo(11L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ObjectStorage.PartETag>> partsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(storage).completeMultipart(eq(OBJECT_KEY), eq(UPLOAD_ID), partsCaptor.capture());
        assertThat(partsCaptor.getValue())
                .extracting(ObjectStorage.PartETag::partNumber)
                .containsExactly(1, 2);

        ArgumentCaptor<FileObject> fileCaptor = ArgumentCaptor.forClass(FileObject.class);
        verify(fileObjectMapper).insert(fileCaptor.capture());
        FileObject file = fileCaptor.getValue();
        assertThat(file.getBizType()).isEqualTo("MINUTES_AUDIO");
        assertThat(file.getBizId()).isEqualTo(MEETING_ID);
        assertThat(file.getObjectKey()).isEqualTo(OBJECT_KEY);
        assertThat(file.getSizeBytes()).isEqualTo(11L);
        assertThat(file.getStatus()).isEqualTo(FileObjectStatus.NORMAL);
        assertThat(file.getUploadedBy()).isEqualTo(USER_ID);

        verify(sessionMapper).markCompleted(UPLOAD_ID, 88L);
        verify(txManager).commit(any());
    }

    @Test
    void completeShouldRollbackAndCleanupWhenMarkCompletedMisses() {
        FileUploadSession session = session(UploadSessionStatus.IN_PROGRESS,
                LocalDateTime.now().plusHours(1), 2, null);
        FileUploadSession locked = session(UploadSessionStatus.IN_PROGRESS,
                LocalDateTime.now().plusHours(1), 2, null);
        session.setSizeBytes(11L);
        locked.setSizeBytes(11L);
        when(sessionMapper.selectByUploadId(UPLOAD_ID)).thenReturn(session);
        stubMatchingParts();
        when(storage.completeMultipart(eq(OBJECT_KEY), eq(UPLOAD_ID), any()))
                .thenReturn(new ObjectStorage.StoredObject(OBJECT_KEY, 11L, "audio/mp4"));
        when(sessionMapper.selectByUploadIdForUpdate(UPLOAD_ID)).thenReturn(locked);
        when(fileObjectMapper.insert(any(FileObject.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, FileObject.class).setId(88L);
            return 1;
        });
        // CAS 未命中：会话已被并发改走 → 事务回滚，file_object 不落库
        when(sessionMapper.markCompleted(UPLOAD_ID, 88L)).thenReturn(0);

        assertThatThrownBy(() -> service.complete(UPLOAD_ID, completeDTO(1, 2), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.UPLOAD_SESSION_NOT_FOUND.getCode());
        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
        // 合并已经发生但没有 DB 记录指向它：best-effort 删除，避免泄漏
        verify(storage).delete(OBJECT_KEY);
        verify(fileObjectMapper).insert(any(FileObject.class));
    }

    @Test
    void completeShouldNotDeleteWhenTransactionFailureTurnsOutCommitted() {
        FileUploadSession inProgress = session(UploadSessionStatus.IN_PROGRESS,
                LocalDateTime.now().plusHours(1), 2, null);
        FileUploadSession completed = session(UploadSessionStatus.COMPLETED,
                LocalDateTime.now().plusHours(1), 2, 9L);
        inProgress.setSizeBytes(11L);
        completed.setSizeBytes(11L);
        // 事务抛"提交结果未知"后重读：其实已经提交成功
        when(sessionMapper.selectByUploadId(UPLOAD_ID)).thenReturn(inProgress, completed);
        stubMatchingParts();
        when(storage.completeMultipart(eq(OBJECT_KEY), eq(UPLOAD_ID), any()))
                .thenReturn(new ObjectStorage.StoredObject(OBJECT_KEY, 11L, "audio/mp4"));
        when(sessionMapper.selectByUploadIdForUpdate(UPLOAD_ID))
                .thenThrow(new RuntimeException("commit unknown"));
        when(fileObjectMapper.selectById(9L)).thenReturn(fileObject(9L));

        FileCompleteVO result = service.complete(UPLOAD_ID, completeDTO(1, 2), USER_ID);

        assertThat(result.getFileId()).isEqualTo(9L);
        // 已提交的对象绝不能删
        verify(storage, never()).delete(anyString());
        verify(sessionMapper, never()).markStatus(anyString(), anyInt());
    }

    @Test
    void completeShouldDeleteAndAbortWhenTransactionFailureNotCompleted() {
        FileUploadSession inProgress = session(UploadSessionStatus.IN_PROGRESS,
                LocalDateTime.now().plusHours(1), 2, null);
        inProgress.setSizeBytes(11L);
        when(sessionMapper.selectByUploadId(UPLOAD_ID)).thenReturn(inProgress);
        stubMatchingParts();
        when(storage.completeMultipart(eq(OBJECT_KEY), eq(UPLOAD_ID), any()))
                .thenReturn(new ObjectStorage.StoredObject(OBJECT_KEY, 11L, "audio/mp4"));
        when(sessionMapper.selectByUploadIdForUpdate(UPLOAD_ID))
                .thenThrow(new RuntimeException("commit unknown"));

        assertThatThrownBy(() -> service.complete(UPLOAD_ID, completeDTO(1, 2), USER_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("commit unknown");

        // 确认未完成：删对象 + CAS 置中止，避免泄漏或永久 IN_PROGRESS
        verify(storage).delete(OBJECT_KEY);
        verify(sessionMapper).markStatus(UPLOAD_ID, UploadSessionStatus.ABORTED);
        verify(fileObjectMapper, never()).insert(any(FileObject.class));
    }

    @Test
    void completeShouldCleanupWhenLockedSessionIsNoLongerInProgress() {
        FileUploadSession inProgress = session(UploadSessionStatus.IN_PROGRESS,
                LocalDateTime.now().plusHours(1), 2, null);
        inProgress.setSizeBytes(11L);
        when(sessionMapper.selectByUploadId(UPLOAD_ID)).thenReturn(inProgress);
        stubMatchingParts();
        when(storage.completeMultipart(eq(OBJECT_KEY), eq(UPLOAD_ID), any()))
                .thenReturn(new ObjectStorage.StoredObject(OBJECT_KEY, 11L, "audio/mp4"));
        // 合并完成后、行锁复查时发现会话已被用户中止/清理任务置过期
        when(sessionMapper.selectByUploadIdForUpdate(UPLOAD_ID)).thenReturn(
                session(UploadSessionStatus.ABORTED, LocalDateTime.now().plusHours(1), 2, null),
                session(UploadSessionStatus.EXPIRED, LocalDateTime.now().minusHours(1), 2, null));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.complete(UPLOAD_ID, completeDTO(1, 2), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(MultipartUploadServiceTest::codeOf)
                    .isEqualTo(ErrorCode.UPLOAD_SESSION_NOT_FOUND.getCode());
        }

        verify(storage, times(2)).delete(OBJECT_KEY);
        verify(sessionMapper, times(2)).markStatus(UPLOAD_ID, UploadSessionStatus.ABORTED);
        verify(fileObjectMapper, never()).insert(any(FileObject.class));
    }

    @Test
    void completeShouldReturnExistingFileWhenStorageReportsConcurrentCompletion() {
        FileUploadSession inProgress = session(UploadSessionStatus.IN_PROGRESS,
                LocalDateTime.now().plusHours(1), 2, null);
        FileUploadSession completed = session(UploadSessionStatus.COMPLETED,
                LocalDateTime.now().plusHours(1), 2, 9L);
        inProgress.setSizeBytes(11L);
        completed.setSizeBytes(11L);
        // 第一次 requireSession 读进行中；completeMultipart 抛 70006 后重读为已完成
        when(sessionMapper.selectByUploadId(UPLOAD_ID)).thenReturn(inProgress, completed);
        stubMatchingParts();
        when(storage.completeMultipart(eq(OBJECT_KEY), eq(UPLOAD_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND));
        when(fileObjectMapper.selectById(9L)).thenReturn(fileObject(9L));

        FileCompleteVO result = service.complete(UPLOAD_ID, completeDTO(1, 2), USER_ID);

        assertThat(result.getFileId()).isEqualTo(9L);
        verify(sessionMapper, never()).selectByUploadIdForUpdate(anyString());
        verify(fileObjectMapper, never()).insert(any(FileObject.class));
    }

    @Test
    void completeShouldDeleteObjectAndAbortOnPostCompleteSizeMismatch() {
        FileUploadSession session = session(UploadSessionStatus.IN_PROGRESS,
                LocalDateTime.now().plusHours(1), 2, null);
        session.setSizeBytes(11L);
        when(sessionMapper.selectByUploadId(UPLOAD_ID)).thenReturn(session);
        stubMatchingParts();
        // 预检通过，但存储返回的合并结果仍不一致（provider 异常/ETag 覆盖等）
        when(storage.completeMultipart(eq(OBJECT_KEY), eq(UPLOAD_ID), any()))
                .thenReturn(new ObjectStorage.StoredObject(OBJECT_KEY, 10L, "audio/mp4"));

        assertThatThrownBy(() -> service.complete(UPLOAD_ID, completeDTO(1, 2), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.UPLOAD_INCOMPLETE.getCode());

        verify(storage).delete(OBJECT_KEY);
        verify(sessionMapper).markStatus(UPLOAD_ID, UploadSessionStatus.ABORTED);
        verify(sessionMapper, never()).selectByUploadIdForUpdate(anyString());
        verify(fileObjectMapper, never()).insert(any(FileObject.class));
    }

    // ---------- abort ----------

    @Test
    void abortShouldMarkSession() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.IN_PROGRESS,
                        LocalDateTime.now().plusHours(1), 3, null));

        service.abort(UPLOAD_ID, USER_ID);

        verify(storage).abortMultipart(OBJECT_KEY, UPLOAD_ID);
        verify(sessionMapper).markStatus(UPLOAD_ID, UploadSessionStatus.ABORTED);
    }

    @Test
    void abortShouldNoOpWhenCompleted() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.COMPLETED,
                        LocalDateTime.now().plusHours(1), 3, 9L));

        service.abort(UPLOAD_ID, USER_ID);

        // 已完成不能清理存储对象，也不能把状态从 10 改走
        verify(storage, never()).abortMultipart(anyString(), anyString());
        verify(sessionMapper, never()).markStatus(anyString(), anyInt());
    }

    @Test
    void abortShouldNoOpWhenAlreadyAbortedOrExpired() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID)).thenReturn(
                session(UploadSessionStatus.ABORTED, LocalDateTime.now().plusHours(1), 3, null),
                session(UploadSessionStatus.EXPIRED, LocalDateTime.now().minusHours(1), 3, null));

        // 重复 DELETE（客户端超时重试的常见场景）必须成功且无副作用
        service.abort(UPLOAD_ID, USER_ID);
        service.abort(UPLOAD_ID, USER_ID);

        verify(storage, never()).abortMultipart(anyString(), anyString());
        verify(sessionMapper, never()).markStatus(anyString(), anyInt());
    }

    @Test
    void abortShouldRejectAnotherUsersUpload() {
        when(sessionMapper.selectByUploadId(UPLOAD_ID))
                .thenReturn(session(UploadSessionStatus.IN_PROGRESS,
                        LocalDateTime.now().plusHours(1), 3, null));

        assertThatThrownBy(() -> service.abort(UPLOAD_ID, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.UPLOAD_SESSION_NOT_FOUND.getCode());
        verify(storage, never()).abortMultipart(anyString(), anyString());
        verify(sessionMapper, never()).markStatus(anyString(), anyInt());
    }
}
