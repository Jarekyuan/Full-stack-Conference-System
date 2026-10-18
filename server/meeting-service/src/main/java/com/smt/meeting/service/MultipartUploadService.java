package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.config.MinutesProperties;
import com.smt.meeting.dto.MultipartCompleteDTO;
import com.smt.meeting.dto.MultipartInitDTO;
import com.smt.meeting.entity.FileObject;
import com.smt.meeting.entity.FileUploadSession;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.enums.FileBizType;
import com.smt.meeting.enums.FileObjectStatus;
import com.smt.meeting.enums.UploadSessionStatus;
import com.smt.meeting.mapper.FileObjectMapper;
import com.smt.meeting.mapper.FileUploadSessionMapper;
import com.smt.meeting.vo.FileCompleteVO;
import com.smt.meeting.vo.MultipartInitVO;
import com.smt.meeting.vo.MultipartPartVO;
import com.smt.meeting.vo.MultipartSessionVO;
import com.smt.meeting.vo.UploadedPartVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 会议纪要音频分片上传（Task 7）。
 *
 * <h3>状态契约</h3>
 * <ul>
 *   <li>会话只有创建者本人可操作（越权一律 70006，不泄露会话是否存在）。</li>
 *   <li>进行中(0) 且未过期才能取分片 URL / 查会话 / 完成；已中止/已过期/超时 → 70006。</li>
 *   <li><b>已完成(10) 是幂等终点</b>：即使 expires_at 已过也允许查询与重复 complete
 *       （返回第一次落库的 file_object），但分片 URL 必须拒绝（70006）。</li>
 * </ul>
 *
 * <h3>为什么 session() 要先判 COMPLETED</h3>
 * 存储实现的 {@code listParts} 对已完成会话抛 70006（COS 的 NoSuchUpload），
 * 若先调 storage 会让"上传完成后轮询会话"变成错误——因此完成态在业务层短路。
 */
@Service
@RequiredArgsConstructor
public class MultipartUploadService {

    private static final Set<String> AUDIO_EXTENSIONS =
            Set.of("m4a", "mp3", "wav", "aac", "ogg");
    private static final Set<String> AUDIO_CONTENT_TYPES = Set.of(
            "audio/mp4", "audio/x-m4a", "audio/mpeg", "audio/wav", "audio/x-wav",
            "audio/aac", "audio/ogg");
    /** 单次批量取 URL 的上限（与 docs/04 契约一致） */
    private static final int MAX_PART_URLS_PER_BATCH = 50;
    /** 分片 URL 有效期（与 cos.part-url-ttl-minutes 默认值一致） */
    private static final Duration PART_URL_TTL = Duration.ofMinutes(60);

    private final ObjectStorage storage;
    private final FileObjectMapper fileObjectMapper;
    private final FileUploadSessionMapper sessionMapper;
    private final MinutesPermissionService permissionService;
    private final MinutesProperties properties;

    /**
     * 初始化分片上传：仅会议组织人/ADMIN，且会议已结束（status=40）时可上传纪要音频。
     */
    public MultipartInitVO init(MultipartInitDTO dto, Long userId) {
        if (!FileBizType.MINUTES_AUDIO.equals(dto.getBizType())) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED.getCode(),
                    "暂只支持会议纪要音频");
        }
        Meeting meeting = permissionService.requireMeeting(dto.getBizId());
        permissionService.assertCanManage(meeting, userId);
        if (!Integer.valueOf(40).equals(meeting.getStatus())) {
            throw new BusinessException(ErrorCode.MINUTES_STATUS_NOT_ALLOWED);
        }
        validateFile(dto);
        long size = dto.getFileSize();
        int chunkSize = properties.getUpload().getChunkSizeBytes();
        int totalParts = (int) ((size + chunkSize - 1) / chunkSize);

        String extension = extension(dto.getFileName());
        String objectKey = "minutes/" + dto.getBizId() + "/"
                + LocalDateTime.now().toLocalDate().toString().substring(0, 7).replace("-", "")
                + "/" + UUID.randomUUID() + "." + extension;
        ObjectStorage.MultipartInit multipart = storage.initMultipart(
                objectKey, dto.getContentType());

        FileUploadSession session = new FileUploadSession();
        session.setUploadId(multipart.uploadId());
        session.setBizType(dto.getBizType());
        session.setBizId(dto.getBizId());
        session.setObjectKey(objectKey);
        session.setOriginalName(dto.getFileName());
        session.setSizeBytes(size);
        session.setContentType(dto.getContentType());
        session.setChunkSize(chunkSize);
        session.setTotalParts(totalParts);
        session.setStatus(UploadSessionStatus.IN_PROGRESS);
        session.setCreatedBy(userId);
        session.setExpiresAt(LocalDateTime.now().plusHours(
                properties.getUpload().getSessionTtlHours()));
        sessionMapper.insert(session);

        return new MultipartInitVO(session.getUploadId(), session.getObjectKey(),
                session.getChunkSize(), session.getTotalParts(), session.getExpiresAt());
    }

    /**
     * 批量生成分片上传 URL（1–50 个，去重后逐个下发）。
     * 已完成会话没有可上传分片，直接 70006；越界分片号按参数错误处理。
     */
    public List<MultipartPartVO> partUrls(String uploadId, List<Integer> partNumbers, Long userId) {
        FileUploadSession session = requireSession(uploadId, userId);
        if (session.getStatus() == UploadSessionStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        if (partNumbers == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "每批分片数需在 1–50 之间");
        }
        Set<Integer> unique = new HashSet<>(partNumbers);
        if (unique.isEmpty() || unique.size() > MAX_PART_URLS_PER_BATCH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "每批分片数需在 1–50 之间");
        }
        List<MultipartPartVO> result = new ArrayList<>();
        for (Integer partNumber : unique) {
            if (partNumber == null || partNumber < 1 || partNumber > session.getTotalParts()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "分片号越界");
            }
            String url = storage.presignPart(session.getObjectKey(), uploadId, partNumber,
                    PART_URL_TTL);
            result.add(new MultipartPartVO(partNumber, url, LocalDateTime.now().plus(PART_URL_TTL)));
        }
        return result;
    }

    /**
     * 会话状态查询：已完成直接返回终态（不调 listParts），进行中则回填已上传分片与缺失分片号。
     */
    public MultipartSessionVO session(String uploadId, Long userId) {
        FileUploadSession session = requireSession(uploadId, userId);
        MultipartSessionVO vo = new MultipartSessionVO();
        vo.setUploadId(uploadId);
        vo.setObjectKey(session.getObjectKey());
        vo.setChunkSize(session.getChunkSize());
        vo.setTotalParts(session.getTotalParts());
        vo.setStatus(session.getStatus());
        vo.setExpiresAt(session.getExpiresAt());
        if (session.getStatus() == UploadSessionStatus.COMPLETED) {
            vo.setUploadedParts(List.of());
            vo.setMissingPartNumbers(List.of());
            return vo;
        }
        List<ObjectStorage.UploadedPart> uploaded = storage.listParts(
                session.getObjectKey(), uploadId);
        Set<Integer> uploadedNumbers = new HashSet<>();
        List<UploadedPartVO> parts = new ArrayList<>();
        for (ObjectStorage.UploadedPart part : uploaded) {
            uploadedNumbers.add(part.partNumber());
            parts.add(new UploadedPartVO(part.partNumber(), part.etag(), part.size()));
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = 1; i <= session.getTotalParts(); i++) {
            if (!uploadedNumbers.contains(i)) {
                missing.add(i);
            }
        }
        vo.setUploadedParts(parts);
        vo.setMissingPartNumbers(missing);
        return vo;
    }

    /**
     * 完成上传：校验分片数量/编号连续 → 合并 → 落 file_object → 会话 CAS 置已完成。
     *
     * <p>对已完成的会话重复调用是幂等的：返回第一次落库的文件信息，不再触碰对象存储。
     * 分片列表不完整（数量不符或编号不是 1..n）时在调用存储前就按 70007 拒绝。</p>
     */
    @Transactional
    public FileCompleteVO complete(String uploadId, MultipartCompleteDTO dto, Long userId) {
        FileUploadSession session = requireSession(uploadId, userId);
        if (session.getStatus() == UploadSessionStatus.COMPLETED) {
            FileObject existing = fileObjectMapper.selectById(session.getFileId());
            if (existing == null) {
                throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
            }
            return new FileCompleteVO(existing.getId(), existing.getObjectKey(),
                    existing.getSizeBytes());
        }
        if (dto == null || dto.getParts() == null
                || dto.getParts().size() != session.getTotalParts()) {
            throw new BusinessException(ErrorCode.UPLOAD_INCOMPLETE);
        }
        List<MultipartCompleteDTO.Part> sorted = dto.getParts().stream()
                .sorted(Comparator.comparingInt(MultipartCompleteDTO.Part::getPartNumber))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getPartNumber() != i + 1) {
                throw new BusinessException(ErrorCode.UPLOAD_INCOMPLETE);
            }
        }
        ObjectStorage.StoredObject object = storage.completeMultipart(
                session.getObjectKey(), uploadId,
                sorted.stream()
                        .map(part -> new ObjectStorage.PartETag(
                                part.getPartNumber(), part.getEtag()))
                        .toList());
        if (object.size() != session.getSizeBytes()) {
            throw new BusinessException(ErrorCode.UPLOAD_INCOMPLETE.getCode(),
                    "上传大小与声明不一致");
        }

        FileObject file = new FileObject();
        file.setBizType(session.getBizType());
        file.setBizId(session.getBizId());
        file.setObjectKey(session.getObjectKey());
        file.setOriginalName(session.getOriginalName());
        file.setSizeBytes(session.getSizeBytes());
        file.setContentType(session.getContentType());
        file.setStatus(FileObjectStatus.NORMAL);
        file.setUploadedBy(userId);
        fileObjectMapper.insert(file);
        sessionMapper.markCompleted(uploadId, file.getId());
        return new FileCompleteVO(file.getId(), file.getObjectKey(), file.getSizeBytes());
    }

    /**
     * 中止上传：已完成是幂等空操作（不能删掉已合并的对象），其余状态清理存储后置已中止。
     */
    public void abort(String uploadId, Long userId) {
        FileUploadSession session = requireSession(uploadId, userId);
        if (session.getStatus() == UploadSessionStatus.COMPLETED) {
            return;
        }
        storage.abortMultipart(session.getObjectKey(), uploadId);
        sessionMapper.markStatus(uploadId, UploadSessionStatus.ABORTED);
    }

    /**
     * 会话归属与有效性校验。
     *
     * <p>已完成(10) 在过期判断之前返回：完成是终态，expires_at 只约束"还能不能继续传"，
     * 不应让已完成的会话在 24 小时后连幂等查询/重复 complete 都报 70006。</p>
     */
    private FileUploadSession requireSession(String uploadId, Long userId) {
        FileUploadSession session = sessionMapper.selectByUploadId(uploadId);
        if (session == null || !session.getCreatedBy().equals(userId)) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        if (session.getStatus() == UploadSessionStatus.COMPLETED) {
            return session;
        }
        if (session.getStatus() == UploadSessionStatus.EXPIRED
                || session.getStatus() == UploadSessionStatus.ABORTED
                || (session.getExpiresAt() != null
                && session.getExpiresAt().isBefore(LocalDateTime.now()))) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        return session;
    }

    private void validateFile(MultipartInitDTO dto) {
        if (dto.getFileSize() > properties.getUpload().getMaxFileSizeBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE.getCode(), "音频不能超过 2GiB");
        }
        String extension = extension(dto.getFileName());
        if (!AUDIO_EXTENSIONS.contains(extension)
                || !AUDIO_CONTENT_TYPES.contains(dto.getContentType())) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED.getCode(),
                    "仅支持 m4a/mp3/wav/aac/ogg");
        }
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        return fileName.substring(dot + 1).toLowerCase();
    }
}
