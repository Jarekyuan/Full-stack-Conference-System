package com.smt.meeting.service;

import com.smt.meeting.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalObjectStorageTest {

    @TempDir
    Path tempDir;

    private LocalObjectStorage storage() {
        StorageProperties properties = new StorageProperties();
        properties.getLocal().setDir(tempDir.toString());
        properties.getLocal().setBaseUrl("http://127.0.0.1:8082");
        return new LocalObjectStorage(properties);
    }

    @Test
    void multipartRoundTrip() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init =
                storage.initMultipart("minutes/1/test.m4a", "audio/mp4");

        storage.writePart(init.uploadId(), 1, "hello ".getBytes());
        storage.writePart(init.uploadId(), 2, "world".getBytes());

        List<ObjectStorage.UploadedPart> parts =
                storage.listParts("minutes/1/test.m4a", init.uploadId());
        assertThat(parts).extracting(ObjectStorage.UploadedPart::partNumber)
                .containsExactly(1, 2);
        assertThat(parts).allSatisfy(part -> assertThat(part.etag()).isNotBlank());

        ObjectStorage.StoredObject object = storage.completeMultipart(
                "minutes/1/test.m4a", init.uploadId(),
                parts.stream()
                        .map(part -> new ObjectStorage.PartETag(part.partNumber(), part.etag()))
                        .toList());

        assertThat(object.size()).isEqualTo(11);
        assertThat(storage.head("minutes/1/test.m4a").size()).isEqualTo(11);
        assertThat(storage.readObject(init.uploadId())).isEqualTo("hello world".getBytes());
        assertThat(storage.contentType(init.uploadId())).isEqualTo("audio/mp4");
    }

    @Test
    void presignedPartUrlIsVerifiable() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init =
                storage.initMultipart("minutes/1/test.m4a", "audio/mp4");
        String url = storage.presignPart("minutes/1/test.m4a", init.uploadId(), 1,
                Duration.ofMinutes(60));
        assertThat(url).contains("/parts/", "sig=", "expires=");
        String query = url.substring(url.indexOf('?') + 1);
        long expires = Long.parseLong(query.split("&")[0].substring("expires=".length()));
        String sig = query.split("&")[1].substring("sig=".length());
        assertThat(storage.verifyToken("PUT", init.uploadId(), 1, expires, sig)).isTrue();
        assertThat(storage.verifyToken("PUT", init.uploadId(), 2, expires, sig)).isFalse();
    }
}
