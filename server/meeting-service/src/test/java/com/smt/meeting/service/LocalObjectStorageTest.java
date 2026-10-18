package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.meeting.config.StorageProperties;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalObjectStorageTest {

    private static final String OBJECT_KEY = "minutes/1/test.m4a";
    private static final String CONTENT_TYPE = "audio/mp4";
    private static final Duration TTL = Duration.ofMinutes(60);

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
                storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);

        storage.writePart(init.uploadId(), 1, utf8("hello "));
        storage.writePart(init.uploadId(), 2, utf8("world"));

        List<ObjectStorage.UploadedPart> parts =
                storage.listParts(OBJECT_KEY, init.uploadId());
        assertThat(parts).extracting(ObjectStorage.UploadedPart::partNumber)
                .containsExactly(1, 2);
        assertThat(parts).allSatisfy(part -> assertThat(part.etag()).isNotBlank());

        ObjectStorage.StoredObject object =
                storage.completeMultipart(OBJECT_KEY, init.uploadId(), partEtags(parts));

        assertThat(object.size()).isEqualTo(11);
        assertThat(storage.head(OBJECT_KEY).size()).isEqualTo(11);
        assertThat(storage.readObject(init.uploadId())).isEqualTo(utf8("hello world"));
        assertThat(storage.contentType(init.uploadId())).isEqualTo(CONTENT_TYPE);

        Path sessionDir = tempDir.resolve(init.uploadId());
        assertThat(sessionDir.resolve("object.bin")).exists();
        assertThat(sessionDir.resolve("object.bin.tmp")).doesNotExist();
        assertThat(sessionDir.resolve("part-1")).doesNotExist();
        assertThat(sessionDir.resolve("part-2")).doesNotExist();
    }

    @Test
    void presignedPartUrlIsVerifiable() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init =
                storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);
        String url = storage.presignPart(OBJECT_KEY, init.uploadId(), 1, TTL);
        assertThat(url).contains("/parts/", "sig=", "expires=");
        Map<String, String> query = queryParams(url);
        long expires = Long.parseLong(query.get("expires"));
        assertThat(storage.verifyToken("PUT", init.uploadId(), 1, expires, query.get("sig")))
                .isTrue();
        assertThat(storage.verifyToken("PUT", init.uploadId(), 2, expires, query.get("sig")))
                .isFalse();
    }

    @Test
    void presignedGetRoundTrip() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init = initCompleted(storage);

        String url = storage.presignGet(OBJECT_KEY, TTL);
        assertThat(url).contains("/objects/", "sig=", "expires=");
        Map<String, String> query = queryParams(url);
        long expires = Long.parseLong(query.get("expires"));
        assertThat(storage.verifyToken("GET", init.uploadId(), null, expires, query.get("sig")))
                .isTrue();
        assertThat(storage.verifyToken("GET", init.uploadId(), 1, expires, query.get("sig")))
                .isFalse();
    }

    @Test
    void expiredTokenIsRejected() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init = storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);
        String url = storage.presignPart(OBJECT_KEY, init.uploadId(), 1,
                Duration.ofSeconds(-1));
        Map<String, String> query = queryParams(url);
        assertThat(storage.verifyToken("PUT", init.uploadId(), 1,
                Long.parseLong(query.get("expires")), query.get("sig"))).isFalse();
    }

    @Test
    void tokenIsBoundToActionUploadAndPart() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit first = storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);
        ObjectStorage.MultipartInit second =
                storage.initMultipart("minutes/1/other.m4a", CONTENT_TYPE);

        String url = storage.presignPart(OBJECT_KEY, first.uploadId(), 1, TTL);
        Map<String, String> query = queryParams(url);
        long expires = Long.parseLong(query.get("expires"));
        String sig = query.get("sig");

        assertThat(storage.verifyToken("GET", first.uploadId(), 1, expires, sig)).isFalse();
        assertThat(storage.verifyToken("PUT", second.uploadId(), 1, expires, sig)).isFalse();
        assertThat(storage.verifyToken("PUT", first.uploadId(), 2, expires, sig)).isFalse();
    }

    @Test
    void presignGetRequiresCompletedObject() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init = storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);

        assertCode(70006, () -> storage.presignGet(OBJECT_KEY, TTL));

        storage.writePart(init.uploadId(), 1, utf8("a"));
        assertCode(70006, () -> storage.presignGet(OBJECT_KEY, TTL));
    }

    @Test
    void tamperedSignatureIsRejected() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init = storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);
        Map<String, String> query =
                queryParams(storage.presignPart(OBJECT_KEY, init.uploadId(), 1, TTL));
        String sig = query.get("sig");
        char replacement = sig.charAt(sig.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = sig.substring(0, sig.length() - 1) + replacement;
        assertThat(storage.verifyToken("PUT", init.uploadId(), 1,
                Long.parseLong(query.get("expires")), tampered)).isFalse();
    }

    @Test
    void missingSessionIsReportedOrNoOp() {
        LocalObjectStorage storage = storage();
        String missingId = UUID.randomUUID().toString();

        assertCode(70006, () -> storage.listParts(OBJECT_KEY, missingId));
        assertCode(70006, () -> storage.presignPart(OBJECT_KEY, missingId, 1, TTL));
        assertCode(70006, () -> storage.completeMultipart(OBJECT_KEY, missingId,
                List.of(new ObjectStorage.PartETag(1, "etag"))));
        assertCode(70006, () -> storage.head(OBJECT_KEY));
        assertCode(70006, () -> storage.presignGet(OBJECT_KEY, TTL));

        assertThatCode(() -> storage.abortMultipart(OBJECT_KEY, missingId))
                .doesNotThrowAnyException();
        assertThatCode(() -> storage.delete(OBJECT_KEY)).doesNotThrowAnyException();
    }

    @Test
    void completedSessionRejectsWritesListsAndPresigns() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init = initCompleted(storage);

        assertCode(70006, () -> storage.writePart(init.uploadId(), 3, utf8("x")));
        assertCode(70006, () -> storage.listParts(OBJECT_KEY, init.uploadId()));
        assertCode(70006, () -> storage.presignPart(OBJECT_KEY, init.uploadId(), 3, TTL));
    }

    @Test
    void abortAfterCompleteIsNoOpAndKeepsObject() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init = initCompleted(storage);

        storage.abortMultipart(OBJECT_KEY, init.uploadId());

        assertThat(storage.head(OBJECT_KEY).size()).isEqualTo(11);
    }

    @Test
    void etagIsMd5HexAndQuotedUppercaseEtagIsAccepted() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init = storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);

        String etag = storage.writePart(init.uploadId(), 1, utf8("hello world"));

        assertThat(etag).isEqualTo("5eb63bbbe01eeed093cb22bb8f5acdc3");
        ObjectStorage.StoredObject object = storage.completeMultipart(
                OBJECT_KEY, init.uploadId(),
                List.of(new ObjectStorage.PartETag(1,
                        "\"" + etag.toUpperCase(Locale.ROOT) + "\"")));
        assertThat(object.size()).isEqualTo(11);
    }

    @Test
    void partsAreListedSortedByPartNumberAndIgnoreNonPartFiles() throws Exception {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init = storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);

        storage.writePart(init.uploadId(), 3, utf8("c"));
        storage.writePart(init.uploadId(), 1, utf8("a"));
        storage.writePart(init.uploadId(), 2, utf8("b"));
        Files.writeString(tempDir.resolve(init.uploadId()).resolve("part-abc"), "junk");

        assertThat(storage.listParts(OBJECT_KEY, init.uploadId()))
                .extracting(ObjectStorage.UploadedPart::partNumber)
                .containsExactly(1, 2, 3);
    }

    @Test
    void completeRejectsWrongEtag() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init = initWithTwoParts(storage);

        assertCode(70007, () -> storage.completeMultipart(OBJECT_KEY, init.uploadId(),
                List.of(new ObjectStorage.PartETag(1, "deadbeef"),
                        new ObjectStorage.PartETag(2, "cafebabe"))));
        assertCode(70006, () -> storage.head(OBJECT_KEY));
    }

    @Test
    void completeRejectsDuplicatePartNumbers() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init = initWithTwoParts(storage);
        List<ObjectStorage.UploadedPart> parts = storage.listParts(OBJECT_KEY, init.uploadId());

        assertCode(70007, () -> storage.completeMultipart(OBJECT_KEY, init.uploadId(),
                List.of(new ObjectStorage.PartETag(1, parts.get(0).etag()),
                        new ObjectStorage.PartETag(1, parts.get(0).etag()),
                        new ObjectStorage.PartETag(2, parts.get(1).etag()))));
    }

    @Test
    void completeRejectsMissingOrNonContiguousPartNumbers() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init = initWithTwoParts(storage);
        List<ObjectStorage.UploadedPart> parts = storage.listParts(OBJECT_KEY, init.uploadId());

        assertCode(70007, () -> storage.completeMultipart(OBJECT_KEY, init.uploadId(),
                List.of(new ObjectStorage.PartETag(1, parts.get(0).etag()))));

        ObjectStorage.MultipartInit gap = storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);
        storage.writePart(gap.uploadId(), 1, utf8("a"));
        storage.writePart(gap.uploadId(), 3, utf8("c"));
        List<ObjectStorage.UploadedPart> gapParts =
                storage.listParts(OBJECT_KEY, gap.uploadId());
        assertCode(70007, () -> storage.completeMultipart(
                OBJECT_KEY, gap.uploadId(), partEtags(gapParts)));
    }

    @Test
    void completeRejectsEmptyOrNullParts() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init = storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);
        storage.writePart(init.uploadId(), 1, utf8("a"));

        assertCode(70007, () -> storage.completeMultipart(
                OBJECT_KEY, init.uploadId(), List.of()));
        assertCode(70007, () -> storage.completeMultipart(
                OBJECT_KEY, init.uploadId(), null));
    }

    @Test
    void completeRejectsObjectKeyMismatch() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init = storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);
        String etag = storage.writePart(init.uploadId(), 1, utf8("a"));

        assertCode(70006, () -> storage.completeMultipart(
                "minutes/1/other.m4a", init.uploadId(),
                List.of(new ObjectStorage.PartETag(1, etag))));
    }

    @Test
    void deleteThenHeadReturns70006AndDeleteIsIdempotent() {
        LocalObjectStorage storage = storage();
        initCompleted(storage);

        storage.delete(OBJECT_KEY);

        assertCode(70006, () -> storage.head(OBJECT_KEY));
        assertThatCode(() -> storage.delete(OBJECT_KEY)).doesNotThrowAnyException();
    }

    @Test
    void pathTraversalUploadIdIsRejected() {
        LocalObjectStorage storage = storage();
        String traversal = "../../etc/passwd";

        assertCode(70006, () -> storage.listParts(OBJECT_KEY, traversal));
        assertCode(70006, () -> storage.contentType(traversal));
        assertCode(70006, () -> storage.objectPath(traversal));
        assertCode(70006, () -> storage.writePart(traversal, 1, utf8("x")));
        assertThat(storage.verifyToken("PUT", traversal, 1,
                System.currentTimeMillis() + 60_000, "sig")).isFalse();
    }

    @Test
    void nullContentTypeFallsBackToOctetStream() {
        LocalObjectStorage storage = storage();
        ObjectStorage.MultipartInit init = storage.initMultipart(OBJECT_KEY, null);
        storage.writePart(init.uploadId(), 1, utf8("a"));

        ObjectStorage.StoredObject object = storage.completeMultipart(
                OBJECT_KEY, init.uploadId(), partEtags(storage.listParts(OBJECT_KEY, init.uploadId())));

        assertThat(object.contentType()).isEqualTo("application/octet-stream");
        assertThat(storage.contentType(init.uploadId()))
                .isEqualTo("application/octet-stream");
    }

    @Test
    void completeWithNoSessionDoesNotCreateObject() {
        LocalObjectStorage storage = storage();
        assertCode(70006, () -> storage.completeMultipart(
                OBJECT_KEY, UUID.randomUUID().toString(),
                List.of(new ObjectStorage.PartETag(1, "etag"))));
        assertThat(tempDir.toFile().listFiles()).isEmpty();
    }

    private ObjectStorage.MultipartInit initCompleted(LocalObjectStorage storage) {
        ObjectStorage.MultipartInit init = initWithTwoParts(storage);
        storage.completeMultipart(OBJECT_KEY, init.uploadId(),
                partEtags(storage.listParts(OBJECT_KEY, init.uploadId())));
        return init;
    }

    private ObjectStorage.MultipartInit initWithTwoParts(LocalObjectStorage storage) {
        ObjectStorage.MultipartInit init = storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);
        storage.writePart(init.uploadId(), 1, utf8("hello "));
        storage.writePart(init.uploadId(), 2, utf8("world"));
        return init;
    }

    private static List<ObjectStorage.PartETag> partEtags(
            List<ObjectStorage.UploadedPart> parts) {
        return parts.stream()
                .map(part -> new ObjectStorage.PartETag(part.partNumber(), part.etag()))
                .toList();
    }

    private static Map<String, String> queryParams(String url) {
        URI uri = URI.create(url);
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int index = pair.indexOf('=');
            params.put(
                    URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertCode(int code, ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
}
