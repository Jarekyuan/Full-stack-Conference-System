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
import com.smt.meeting.enums.UploadSessionStatus;
import com.smt.meeting.mapper.FileObjectMapper;
import com.smt.meeting.mapper.FileUploadSessionMapper;
import com.smt.meeting.vo.FileCompleteVO;
import com.smt.meeting.vo.MultipartInitVO;
import com.smt.meeting.vo.MultipartSessionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 7：分片上传业务层。
 *
 * <p>重点钉住两条容易写反的契约：</p>
 * <ul>
 *   <li><b>已完成(10) 是幂等终点</b>——session() 不得调 storage.listParts（存储层对完成态抛 70006），
 *       重复 complete 返回第一次落库的文件；而 partUrls 对完成态必须拒绝。</li>
 *   <li><b>归属校验先于一切</b>——别人的 uploadId 与"不存在"返回同一个 70006。</li>
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
    private MinutesProperties properties;
    private MultipartUploadService service;

    @BeforeEach
    void setUp() {
        storage = mock(ObjectStorage.class);
        fileObjectMapper = mock(FileObjectMapper.class);
        sessionMapper = mock(FileUploadSessionMapper.class);
        permissionService = mock(MinutesPermissionService.class);
        properties = new MinutesProperties();
        service = new MultipartUploadService(storage, fileObjectMapper, sessionMapper,
                permissionService, properties);
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

    private static int codeOf(Throwable throwable) {
        return ((BusinessException) throwable).getCode();
    }

    // ---------- init ----------

    @Test
    void initShouldRejectWhenMeetingNotEnded() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(30));

        assertThatThrownBy(() -> service.init(initDTO(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MultipartUploadServiceTest::codeOf)
                .isEqualTo(ErrorCode.MINUTES_STATUS_NOT_ALLOWED.getCode());
        verify(storage, never()).initMultipart(anyString(), anyString());
        verify(sessionMapper, never()).insert(any(FileUploadSession.class));
    }

    @Test
    void initShouldRejectUnsupportedExtension() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
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
    void initShouldCreateSession() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting(40));
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
        assertThat(result.getUploadedParts()).isEmpty();
        assertThat(result.getMissingPartNumbers()).isEmpty();
        verify(storage, never()).listParts(anyString(), anyString());
    }

    // ---------- partUrls ----------

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
        FileObject existing = new FileObject();
        existing.setId(9L);
        existing.setObjectKey(OBJECT_KEY);
        existing.setSizeBytes(11L);
        when(fileObjectMapper.selectById(9L)).thenReturn(existing);

        // 空 DTO（parts=null）：已完成分支必须在解引用前返回
        FileCompleteVO result = service.complete(UPLOAD_ID, new MultipartCompleteDTO(), USER_ID);

        assertThat(result.getFileId()).isEqualTo(9L);
        assertThat(result.getObjectKey()).isEqualTo(OBJECT_KEY);
        verify(storage, never()).completeMultipart(anyString(), anyString(), any());
        verify(fileObjectMapper, never()).insert(any(FileObject.class));
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
        verify(storage, never()).completeMultipart(anyString(), anyString(), any());
    }

    @Test
    void completeShouldCreateFileObject() {
        FileUploadSession session = session(UploadSessionStatus.IN_PROGRESS,
                LocalDateTime.now().plusHours(1), 2, null);
        session.setBizType("MINUTES_AUDIO");
        session.setBizId(MEETING_ID);
        session.setSizeBytes(10L);
        when(sessionMapper.selectByUploadId(UPLOAD_ID)).thenReturn(session);
        when(storage.completeMultipart(eq(OBJECT_KEY), eq(UPLOAD_ID), any()))
                .thenReturn(new ObjectStorage.StoredObject(OBJECT_KEY, 10L, "audio/mp4"));
        when(fileObjectMapper.insert(any(FileObject.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, FileObject.class).setId(88L);
            return 1;
        });

        // 乱序提交：服务端排序后再交给存储
        FileCompleteVO result = service.complete(UPLOAD_ID, completeDTO(2, 1), USER_ID);

        assertThat(result.getFileId()).isEqualTo(88L);
        assertThat(result.getObjectKey()).isEqualTo(OBJECT_KEY);
        assertThat(result.getSizeBytes()).isEqualTo(10L);

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
        assertThat(file.getSizeBytes()).isEqualTo(10L);
        assertThat(file.getStatus()).isEqualTo(FileObjectStatus.NORMAL);
        assertThat(file.getUploadedBy()).isEqualTo(USER_ID);

        verify(sessionMapper).markCompleted(UPLOAD_ID, 88L);
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
}
