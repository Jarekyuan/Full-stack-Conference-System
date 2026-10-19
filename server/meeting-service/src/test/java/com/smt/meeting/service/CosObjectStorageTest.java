package com.smt.meeting.service;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.CompleteMultipartUploadRequest;
import com.qcloud.cos.model.CompleteMultipartUploadResult;
import com.qcloud.cos.model.ObjectMetadata;
import com.smt.meeting.config.CosProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * COS 实现细节的离线单测：真实 SDK 会对 complete 的分片列表原地排序，
 * 传入 Stream.toList() 这类不可变列表会在真实调用时抛 UnsupportedOperationException。
 */
class CosObjectStorageTest {

    @Test
    void completeMultipartPassesMutablePartListToSdk() {
        COSClient client = mock(COSClient.class);
        CosProperties properties = new CosProperties();
        properties.setBucket("jonas-cos-test");
        CosObjectStorage storage = new CosObjectStorage(client, properties);

        CompleteMultipartUploadResult result = new CompleteMultipartUploadResult();
        when(client.completeMultipartUpload(any())).thenReturn(result);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(12L);
        metadata.setContentType("audio/mp4");
        when(client.getObjectMetadata(any())).thenReturn(metadata);

        storage.completeMultipart("minutes/1/x.m4a", "upload-1", List.of(
                new ObjectStorage.PartETag(2, "etag-2"),
                new ObjectStorage.PartETag(1, "etag-1")));

        ArgumentCaptor<CompleteMultipartUploadRequest> captor =
                ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(client).completeMultipartUpload(captor.capture());
        assertThatCode(() -> captor.getValue().getPartETags()
                .sort(Comparator.comparingInt(part -> part.getPartNumber())))
                .doesNotThrowAnyException();
    }
}
