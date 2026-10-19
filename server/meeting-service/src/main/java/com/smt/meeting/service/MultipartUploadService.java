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
import com.smt.meeting.enums.MeetingStatusEnum;
import com.smt.meeting.enums.UploadSessionStatus;
import com.smt.meeting.mapper.FileObjectMapper;
import com.smt.meeting.mapper.FileUploadSessionMapper;
import com.smt.meeting.vo.FileCompleteVO;
import com.smt.meeting.vo.MultipartInitVO;
import com.smt.meeting.vo.MultipartPartVO;
import com.smt.meeting.vo.MultipartSessionVO;
import com.smt.meeting.vo.UploadedPartVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 * <h3>complete 的顺序约束（重要）</h3>
 * <ol>
 *   <li>先在 {@code completeMultipart} <b>之前</b>用 {@code listParts} 做大小预检——
 *       两个 provider 的 complete 都会消费 uploadId，合并后才发现大小不符就无法重试了；</li>
 *   <li>{@code completeMultipart} 在 DB 事务<b>之外</b>执行，远端调用不占数据库连接；</li>
 *   <li>DB 落库放在短事务里：行锁复查状态 → 插 file_object → CAS 标记完成，
 *       标记 0 行即回滚（防止并发完成产生两条 file_object）；</li>
 *   <li>合并成功后若 DB 事务失败，best-effort 删除已合并对象；
 *       合并后大小仍不符则删除对象并把会话置为已中止，绝不留在进行中。</li>
 * </ol>
 */
@Slf4j
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
    /**
     * 分片总数上限：与 COS ListParts 单页上限（1000）对齐。
     * complete 前的 size 预检依赖 listParts 全量返回；超过 1000 会被截断，预检误判为不完整。
     */
    private static final int MAX_TOTAL_PARTS = 1000;
    /** 对象键里的月份目录固定为 6 位 yyyyMM */
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    private final ObjectStorage storage;
    private final FileObjectMapper fileObjectMapper;
    private final FileUploadSessionMapper sessionMapper;
    private final MinutesPermissionService permissionService;
    private final MinutesProperties properties;
    private final TransactionTemplate transactionTemplate;

    /**
     * 初始化分片上传：仅会议组织人/ADMIN，且会议已结束（status=40）时可上传纪要音频。
     *
     * <p>存储会话已创建但数据库插入失败时，best-effort 中止存储会话，避免留下孤儿 upload。</p>
     */
    public MultipartInitVO init(MultipartInitDTO dto, Long userId) {
        if (!FileBizType.MINUTES_AUDIO.equals(dto.getBizType())) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED.getCode(),
                    "暂只支持会议纪要音频");
        }
        Meeting meeting = permissionService.requireMeeting(dto.getBizId());
        permissionService.assertCanManage(meeting, userId);
        if (!Integer.valueOf(MeetingStatusEnum.FINISHED.getCode()).equals(meeting.getStatus())) {
            throw new BusinessException(ErrorCode.MINUTES_STATUS_NOT_ALLOWED);
        }
        validateFile(dto);
        long size = dto.getFileSize();
        int chunkSize = properties.getUpload().getChunkSizeBytes();
        // 先用 long 判断再做 int 转换：chunkSize 配置很小时，2GiB/1 会溢出成负数绕过上限
        long totalPartsValue = (size + chunkSize - 1) / chunkSize;
        if (totalPartsValue > MAX_TOTAL_PARTS) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(),
                    "分片数超过 1000，请增大分片大小");
        }
        int totalParts = (int) totalPartsValue;

        String extension = extension(dto.getFileName());
        String objectKey = "minutes/" + dto.getBizId() + "/"
                + MONTH_FORMAT.format(LocalDateTime.now())
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
        try {
            sessionMapper.insert(session);
        } catch (RuntimeException e) {
            // 数据库没有会话行 → 存储侧 upload 永远不会被 complete/abort，主动清掉
            abortQuietly(objectKey, multipart.uploadId());
            throw e;
        }

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
        Duration ttl = Duration.ofMinutes(properties.getUpload().getPartUrlTtlMinutes());
        List<MultipartPartVO> result = new ArrayList<>();
        for (Integer partNumber : unique) {
            if (partNumber == null || partNumber < 1 || partNumber > session.getTotalParts()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "分片号越界");
            }
            String url = storage.presignPart(session.getObjectKey(), uploadId, partNumber, ttl);
            result.add(new MultipartPartVO(partNumber, url, LocalDateTime.now().plus(ttl)));
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
            vo.setFileId(session.getFileId());
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
     * 完成上传：校验 → 大小预检 → 合并（事务外）→ 短事务落库。
     *
     * <p>对已完成的会话重复调用是幂等的：返回第一次落库的文件信息，不再触碰对象存储。
     * 空/null 分片列表、数量不符、编号不连续、元素为空都按 70007 处理。</p>
     */
    public FileCompleteVO complete(String uploadId, MultipartCompleteDTO dto, Long userId) {
        FileUploadSession session = requireSession(uploadId, userId);
        if (session.getStatus() == UploadSessionStatus.COMPLETED) {
            return existingFile(session);
        }
        List<MultipartCompleteDTO.Part> parts = validatedParts(dto, session);

        // 预检必须在 completeMultipart 之前：complete 会消费 uploadId，事后再发现大小不符无法重试。
        // listParts 抛 70006 时可能是并发 complete 已合并（COS NoSuchUpload），先按幂等恢复。
        List<ObjectStorage.UploadedPart> uploaded;
        try {
            uploaded = storage.listParts(session.getObjectKey(), uploadId);
        } catch (BusinessException e) {
            if (e.getCode() == ErrorCode.UPLOAD_SESSION_NOT_FOUND.getCode()) {
                return recoverIfCompleted(uploadId, userId, e);
            }
            throw e;
        }
        long uploadedSize = uploaded.stream()
                .mapToLong(ObjectStorage.UploadedPart::size)
                .sum();
        if (uploadedSize != session.getSizeBytes()) {
            throw new BusinessException(ErrorCode.UPLOAD_INCOMPLETE.getCode(),
                    "上传大小与声明不一致");
        }

        ObjectStorage.StoredObject object;
        try {
            object = storage.completeMultipart(session.getObjectKey(), uploadId,
                    parts.stream()
                            .map(part -> new ObjectStorage.PartETag(
                                    part.getPartNumber(), part.getEtag()))
                            .toList());
        } catch (BusinessException e) {
            // 并发完成/已被合并：会话若已变成完成态，按幂等返回第一次的文件
            if (e.getCode() == ErrorCode.UPLOAD_SESSION_NOT_FOUND.getCode()) {
                return recoverIfCompleted(uploadId, userId, e);
            }
            throw e;
        }

        if (object.size() != session.getSizeBytes()) {
            // 合并已消费 uploadId：删对象 + 中止会话，绝不能留在进行中（否则永远无法重试）
            deleteQuietly(object.objectKey());
            markStatusQuietly(uploadId, UploadSessionStatus.ABORTED);
            throw new BusinessException(ErrorCode.UPLOAD_INCOMPLETE.getCode(),
                    "上传大小与声明不一致");
        }

        try {
            return transactionTemplate.execute(status -> persistCompleted(uploadId, userId));
        } catch (RuntimeException e) {
            // DB 事务提交结果未知：先确认是否其实已提交，再决定能不能删对象
            return recoverAfterPersistenceFailure(uploadId, userId, object.objectKey(), e);
        }
    }

    /**
     * 中止上传（幂等）：已完成/已中止/已过期都是空操作。
     *
     * <p>不复用 {@link #requireSession}：那条路径会把 ABORTED/EXPIRED 当错误拒绝，
     * 而"重复 DELETE"应当是成功（客户端超时重试的常见场景）。</p>
     */
    public void abort(String uploadId, Long userId) {
        FileUploadSession session = sessionMapper.selectByUploadId(uploadId);
        if (session == null || !session.getCreatedBy().equals(userId)) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        if (session.getStatus() == UploadSessionStatus.COMPLETED
                || session.getStatus() == UploadSessionStatus.ABORTED
                || session.getStatus() == UploadSessionStatus.EXPIRED) {
            return;
        }
        storage.abortMultipart(session.getObjectKey(), uploadId);
        sessionMapper.markStatus(uploadId, UploadSessionStatus.ABORTED);
    }

    // ---------- 内部 ----------

    /**
     * 短事务：行锁复查 → 插 file_object → CAS 标记完成。
     * markCompleted 影响 0 行说明会话已被并发改走，抛 70006 让整个事务回滚（file_object 不落库）。
     */
    private FileCompleteVO persistCompleted(String uploadId, Long userId) {
        FileUploadSession locked = sessionMapper.selectByUploadIdForUpdate(uploadId);
        if (locked == null || !locked.getCreatedBy().equals(userId)) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        if (locked.getStatus() == UploadSessionStatus.COMPLETED) {
            return existingFile(locked);
        }
        if (locked.getStatus() != UploadSessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        FileObject file = new FileObject();
        file.setBizType(locked.getBizType());
        file.setBizId(locked.getBizId());
        file.setObjectKey(locked.getObjectKey());
        file.setOriginalName(locked.getOriginalName());
        file.setSizeBytes(locked.getSizeBytes());
        file.setContentType(locked.getContentType());
        file.setStatus(FileObjectStatus.NORMAL);
        file.setUploadedBy(userId);
        fileObjectMapper.insert(file);
        int rows = sessionMapper.markCompleted(uploadId, file.getId());
        if (rows == 0) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        return new FileCompleteVO(file.getId(), file.getObjectKey(), file.getSizeBytes());
    }

    /**
     * 校验分片列表：null/空、数量不符、元素为空、编号不连续/重复都按 70007。
     *
     * @return 按分片号升序的副本（存储实现要求 1..n 顺序）
     */
    private List<MultipartCompleteDTO.Part> validatedParts(MultipartCompleteDTO dto,
                                                           FileUploadSession session) {
        if (dto == null || dto.getParts() == null || dto.getParts().isEmpty()
                || dto.getParts().size() != session.getTotalParts()) {
            throw new BusinessException(ErrorCode.UPLOAD_INCOMPLETE);
        }
        for (MultipartCompleteDTO.Part part : dto.getParts()) {
            if (part == null || part.getPartNumber() == null
                    || part.getEtag() == null || part.getEtag().isBlank()) {
                throw new BusinessException(ErrorCode.UPLOAD_INCOMPLETE);
            }
        }
        List<MultipartCompleteDTO.Part> sorted = dto.getParts().stream()
                .sorted(Comparator.comparingInt(MultipartCompleteDTO.Part::getPartNumber))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getPartNumber() != i + 1) {
                throw new BusinessException(ErrorCode.UPLOAD_INCOMPLETE);
            }
        }
        return sorted;
    }

    /**
     * 70006 恢复：并发 complete 可能已经把会话改成完成态（COS 的 complete/listParts 都会
     * 对已消费的 uploadId 抛 NoSuchUpload）。重读会话——已完成则返回第一次落库的文件，
     * 否则抛回原始 70006；重读本身失败时同样抛回原始异常（不掩盖真实原因）。
     */
    private FileCompleteVO recoverIfCompleted(String uploadId, Long userId,
                                              BusinessException original) {
        FileUploadSession latest;
        try {
            latest = sessionMapper.selectByUploadId(uploadId);
        } catch (RuntimeException rereadFailure) {
            throw original;
        }
        if (latest != null && latest.getCreatedBy().equals(userId)
                && latest.getStatus() == UploadSessionStatus.COMPLETED) {
            return existingFile(latest);
        }
        throw original;
    }

    /**
     * DB 落库失败（提交结果未知）时的恢复。
     *
     * <ul>
     *   <li>重读为已完成 → 说明事务其实已提交，直接返回文件，<b>绝不删除对象</b>；</li>
     *   <li>重读本身失败 → 无法确认是否提交，保留对象（删除可能误删已提交对象）并抛回原异常；</li>
     *   <li>确认未完成 → best-effort 删除对象并 CAS 置为已中止，再抛回原异常。</li>
     * </ul>
     */
    private FileCompleteVO recoverAfterPersistenceFailure(String uploadId, Long userId,
                                                          String objectKey,
                                                          RuntimeException original) {
        FileUploadSession latest;
        try {
            latest = sessionMapper.selectByUploadId(uploadId);
        } catch (RuntimeException rereadFailure) {
            throw original;
        }
        if (latest != null && latest.getCreatedBy().equals(userId)
                && latest.getStatus() == UploadSessionStatus.COMPLETED) {
            return existingFile(latest);
        }
        deleteQuietly(objectKey);
        markStatusQuietly(uploadId, UploadSessionStatus.ABORTED);
        throw original;
    }

    /** 读取会话已关联的文件；数据不一致（file_id 悬空）按会话不存在处理。 */
    private FileCompleteVO existingFile(FileUploadSession session) {
        FileObject existing = fileObjectMapper.selectById(session.getFileId());
        if (existing == null) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        return new FileCompleteVO(existing.getId(), existing.getObjectKey(),
                existing.getSizeBytes());
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
        Long maxBytes = properties.getUpload().getMaxFileSizeBytes();
        if (dto.getFileSize() > maxBytes) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE.getCode(),
                    "音频不能超过 " + maxBytes + " 字节");
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

    private void abortQuietly(String objectKey, String uploadId) {
        try {
            storage.abortMultipart(objectKey, uploadId);
        } catch (Exception e) {
            log.warn("[分片上传] 初始化失败后清理存储会话失败，uploadId={}", uploadId, e);
        }
    }

    private void deleteQuietly(String objectKey) {
        try {
            storage.delete(objectKey);
        } catch (Exception e) {
            log.warn("[分片上传] 清理已合并对象失败，objectKey={}", objectKey, e);
        }
    }

    private void markStatusQuietly(String uploadId, int status) {
        try {
            sessionMapper.markStatus(uploadId, status);
        } catch (Exception e) {
            log.warn("[分片上传] 更新会话状态失败，uploadId={}, status={}", uploadId, status, e);
        }
    }
}
