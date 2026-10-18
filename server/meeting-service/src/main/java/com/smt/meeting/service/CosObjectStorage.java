package com.smt.meeting.service;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.AbortMultipartUploadRequest;
import com.qcloud.cos.model.CompleteMultipartUploadRequest;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.GetObjectMetadataRequest;
import com.qcloud.cos.model.InitiateMultipartUploadRequest;
import com.qcloud.cos.model.ListPartsRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PartListing;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.config.CosProperties;
import lombok.RequiredArgsConstructor;

import java.net.URL;
import java.time.Duration;
import java.util.Date;
import java.util.List;

/**
 * 腾讯云 COS 实现。
 *
 * <p>COS 的 {@code NoSuchUpload}/{@code NoSuchKey}/404 统一翻译为 70006，
 * {@code InvalidPart}/{@code InvalidPartOrder} 翻译为 70007，其余 {@link CosServiceException} 原样抛出。</p>
 */
@RequiredArgsConstructor
public class CosObjectStorage implements ObjectStorage {

    private static final String NO_SUCH_UPLOAD = "NoSuchUpload";
    private static final String NO_SUCH_KEY = "NoSuchKey";
    private static final String INVALID_PART = "InvalidPart";
    private static final String INVALID_PART_ORDER = "InvalidPartOrder";

    private final COSClient client;
    private final CosProperties properties;

    @Override
    public MultipartInit initMultipart(String objectKey, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType);
        InitiateMultipartUploadRequest request =
                new InitiateMultipartUploadRequest(properties.getBucket(), objectKey, metadata);
        String uploadId = client.initiateMultipartUpload(request).getUploadId();
        return new MultipartInit(uploadId);
    }

    @Override
    public List<UploadedPart> listParts(String objectKey, String uploadId) {
        ListPartsRequest request =
                new ListPartsRequest(properties.getBucket(), objectKey, uploadId);
        try {
            PartListing listing = client.listParts(request);
            return listing.getParts() == null
                    ? List.of()
                    : listing.getParts().stream()
                            .map(part -> new UploadedPart(
                                    part.getPartNumber(), part.getETag(), part.getSize()))
                            .toList();
        } catch (CosServiceException e) {
            throw translate(e);
        }
    }

    /**
     * 生成分片上传预签名 URL。
     *
     * <p>预签名本身不产生 COS 服务端调用，因此无法廉价校验 uploadId 是否存在；
     * 客户端拿着无效 uploadId 上传时 COS 会直接返回 {@code NoSuchUpload}（不会经过本服务）。</p>
     */
    @Override
    public String presignPart(String objectKey, String uploadId, int partNumber, Duration ttl) {
        if (objectKey == null || objectKey.isBlank()
                || uploadId == null || uploadId.isBlank() || partNumber < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                properties.getBucket(), objectKey, HttpMethodName.PUT);
        request.setExpiration(new Date(System.currentTimeMillis() + ttl.toMillis()));
        request.addRequestParameter("partNumber", String.valueOf(partNumber));
        request.addRequestParameter("uploadId", uploadId);
        URL url = client.generatePresignedUrl(request);
        return url.toString();
    }

    @Override
    public StoredObject completeMultipart(String objectKey, String uploadId, List<PartETag> parts) {
        List<com.qcloud.cos.model.PartETag> cosParts = parts.stream()
                .sorted((a, b) -> Integer.compare(a.partNumber(), b.partNumber()))
                .map(part -> new com.qcloud.cos.model.PartETag(part.partNumber(), part.etag()))
                .toList();
        CompleteMultipartUploadRequest request = new CompleteMultipartUploadRequest(
                properties.getBucket(), objectKey, uploadId, cosParts);
        try {
            client.completeMultipartUpload(request);
        } catch (CosServiceException e) {
            throw translate(e);
        }
        return head(objectKey);
    }

    @Override
    public void abortMultipart(String objectKey, String uploadId) {
        try {
            client.abortMultipartUpload(new AbortMultipartUploadRequest(
                    properties.getBucket(), objectKey, uploadId));
        } catch (CosServiceException e) {
            if (isNoSuchUpload(e)) {
                return;
            }
            throw translate(e);
        }
    }

    @Override
    public StoredObject head(String objectKey) {
        try {
            ObjectMetadata metadata = client.getObjectMetadata(
                    new GetObjectMetadataRequest(properties.getBucket(), objectKey));
            return new StoredObject(objectKey, metadata.getContentLength(),
                    metadata.getContentType() == null
                            ? "application/octet-stream"
                            : metadata.getContentType());
        } catch (CosServiceException e) {
            throw translate(e);
        }
    }

    @Override
    public String presignGet(String objectKey, Duration ttl) {
        // 先确认对象存在，避免给调用方一个上传后必然 404 的下载 URL
        head(objectKey);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                properties.getBucket(), objectKey, HttpMethodName.GET);
        request.setExpiration(new Date(System.currentTimeMillis() + ttl.toMillis()));
        return client.generatePresignedUrl(request).toString();
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.deleteObject(properties.getBucket(), objectKey);
        } catch (CosServiceException e) {
            if (isNoSuchKey(e)) {
                return;
            }
            throw translate(e);
        }
    }

    private RuntimeException translate(CosServiceException e) {
        if (isNoSuchUpload(e) || isNoSuchKey(e)) {
            return new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        String errorCode = e.getErrorCode();
        if (INVALID_PART.equals(errorCode) || INVALID_PART_ORDER.equals(errorCode)) {
            return new BusinessException(ErrorCode.UPLOAD_INCOMPLETE);
        }
        return e;
    }

    private static boolean isNoSuchUpload(CosServiceException e) {
        return e.getStatusCode() == 404 || NO_SUCH_UPLOAD.equals(e.getErrorCode());
    }

    private static boolean isNoSuchKey(CosServiceException e) {
        return e.getStatusCode() == 404 || NO_SUCH_KEY.equals(e.getErrorCode());
    }
}
