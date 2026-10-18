package com.smt.meeting.service;

import java.time.Duration;
import java.util.List;

public interface ObjectStorage {

    MultipartInit initMultipart(String objectKey, String contentType);

    List<UploadedPart> listParts(String objectKey, String uploadId);

    String presignPart(String objectKey, String uploadId, int partNumber, Duration ttl);

    StoredObject completeMultipart(String objectKey, String uploadId, List<PartETag> parts);

    void abortMultipart(String objectKey, String uploadId);

    StoredObject head(String objectKey);

    String presignGet(String objectKey, Duration ttl);

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
