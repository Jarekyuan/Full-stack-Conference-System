package com.smt.meeting.service;

import java.time.Duration;
import java.util.List;

/**
 * 对象存储抽象：会议纪要音频分片上传（COS / 本地文件系统）。
 *
 * <p>错误契约（所有实现必须一致）：</p>
 * <ul>
 *   <li>{@link #listParts}：会话不存在或上传已完成 → 70006（对应 COS {@code NoSuchUpload}）。</li>
 *   <li>{@link #completeMultipart}：会话不存在 → 70006；分片为空、数量不符、编号不是 1..n、
 *       重复编号或 ETag 与已上传分片不匹配 → 70007。</li>
 *   <li>{@link #abortMultipart}：会话不存在或已上传完成 → 幂等空操作。</li>
 *   <li>{@link #delete}：对象不存在 → 幂等空操作。</li>
 *   <li>{@link #head} / {@link #presignGet}：对象不存在 → 70006。</li>
 *   <li>{@link #presignPart}：校验 uploadId 与分片编号；会话不存在或已完成 → 70006。</li>
 * </ul>
 *
 * <p>实现需把 provider 错误翻译为上述错误码，调用方只依赖 {@link com.smt.common.result.ErrorCode}。</p>
 */
public interface ObjectStorage {

    /**
     * 初始化分片上传会话。
     *
     * @param objectKey   对象键（如 {@code minutes/1/202610/uuid.m4a}）
     * @param contentType MIME 类型；本地实现允许 null 并回退 {@code application/octet-stream}
     * @return 会话信息（uploadId）
     */
    MultipartInit initMultipart(String objectKey, String contentType);

    /**
     * 列出已上传分片。
     *
     * @throws com.smt.common.exception.BusinessException 70006：会话不存在或上传已完成
     */
    List<UploadedPart> listParts(String objectKey, String uploadId);

    /**
     * 生成单片上传的预签名 URL。
     *
     * <p>COS 实现只生成本地签名、不发起服务端请求，uploadId 的有效性由 COS 在实际上传时校验；
     * 本地实现会立即校验会话与分片编号。</p>
     *
     * @throws com.smt.common.exception.BusinessException 70006：会话不存在或已完成（本地实现）
     */
    String presignPart(String objectKey, String uploadId, int partNumber, Duration ttl);

    /**
     * 完成分片上传并合并为完整对象。
     *
     * @param parts 客户端确认的分片编号与 ETag
     * @throws com.smt.common.exception.BusinessException 70006：会话不存在；
     *         70007：分片为空/不完整/编号不连续/重复/ETag 不匹配
     */
    StoredObject completeMultipart(String objectKey, String uploadId, List<PartETag> parts);

    /**
     * 中止分片上传会话。会话不存在或已上传完成时为幂等空操作。
     */
    void abortMultipart(String objectKey, String uploadId);

    /**
     * 查询已完成对象的元数据。
     *
     * @throws com.smt.common.exception.BusinessException 70006：对象不存在
     */
    StoredObject head(String objectKey);

    /**
     * 生成下载用的预签名 URL。
     *
     * @throws com.smt.common.exception.BusinessException 70006：对象不存在
     */
    String presignGet(String objectKey, Duration ttl);

    /**
     * 删除对象。对象不存在时为幂等空操作。
     */
    void delete(String objectKey);

    record MultipartInit(String uploadId) {
    }

    record UploadedPart(int partNumber, String etag, long size) {
    }

    record PartETag(int partNumber, String etag) {
    }

    record StoredObject(String objectKey, long size, String contentType) {
    }
}
