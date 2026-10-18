package com.smt.meeting.service;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.AbortMultipartUploadRequest;
import com.qcloud.cos.model.CompleteMultipartUploadRequest;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.GetObjectMetadataRequest;
import com.qcloud.cos.model.InitiateMultipartUploadRequest;
import com.qcloud.cos.model.ListPartsRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PartListing;
import com.smt.meeting.config.CosProperties;
import lombok.RequiredArgsConstructor;

import java.net.URL;
import java.time.Duration;
import java.util.Date;
import java.util.List;

@RequiredArgsConstructor
public class CosObjectStorage implements ObjectStorage {

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
        PartListing listing = client.listParts(request);
        return listing.getParts().stream()
                .map(part -> new UploadedPart(
                        part.getPartNumber(), part.getETag(), part.getSize()))
                .toList();
    }

    @Override
    public String presignPart(String objectKey, String uploadId, int partNumber, Duration ttl) {
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
        client.completeMultipartUpload(request);
        return head(objectKey);
    }

    @Override
    public void abortMultipart(String objectKey, String uploadId) {
        client.abortMultipartUpload(new AbortMultipartUploadRequest(
                properties.getBucket(), objectKey, uploadId));
    }

    @Override
    public StoredObject head(String objectKey) {
        ObjectMetadata metadata = client.getObjectMetadata(
                new GetObjectMetadataRequest(properties.getBucket(), objectKey));
        return new StoredObject(objectKey, metadata.getContentLength(),
                metadata.getContentType() == null
                        ? "application/octet-stream"
                        : metadata.getContentType());
    }

    @Override
    public String presignGet(String objectKey, Duration ttl) {
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                properties.getBucket(), objectKey, HttpMethodName.GET);
        request.setExpiration(new Date(System.currentTimeMillis() + ttl.toMillis()));
        return client.generatePresignedUrl(request).toString();
    }

    @Override
    public void delete(String objectKey) {
        client.deleteObject(properties.getBucket(), objectKey);
    }
}
