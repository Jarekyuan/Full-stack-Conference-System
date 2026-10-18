package com.smt.meeting.controller;

import com.smt.meeting.config.MinutesProperties;
import com.smt.meeting.config.StorageProperties;
import com.smt.meeting.service.LocalObjectStorage;
import com.smt.meeting.service.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LocalFileControllerTest {

    private static final String OBJECT_KEY = "minutes/1/test.m4a";
    private static final String CONTENT_TYPE = "audio/mp4";
    private static final Duration TTL = Duration.ofMinutes(60);

    @TempDir
    Path tempDir;

    private LocalObjectStorage storage;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.getLocal().setDir(tempDir.toString());
        storageProperties.getLocal().setBaseUrl("http://127.0.0.1:8082");
        storage = new LocalObjectStorage(storageProperties);

        MinutesProperties minutesProperties = new MinutesProperties();
        minutesProperties.getUpload().setChunkSizeBytes(8);

        mockMvc = MockMvcBuilders.standaloneSetup(
                new LocalFileController(storage, minutesProperties)).build();
    }

    @Test
    void putRejectsInvalidSignature() throws Exception {
        ObjectStorage.MultipartInit init = storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);

        mockMvc.perform(put("/files/local/parts/{uploadId}/1", init.uploadId())
                        .param("expires", String.valueOf(System.currentTimeMillis() + 60_000))
                        .param("sig", "invalid")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(utf8("hello")))
                .andExpect(status().isForbidden());
        assertThat(tempDir.resolve(init.uploadId()).resolve("part-1")).doesNotExist();
    }

    @Test
    void putRejectsOversizedPart() throws Exception {
        ObjectStorage.MultipartInit init = storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);
        String url = storage.presignPart(OBJECT_KEY, init.uploadId(), 1, TTL);
        Map<String, String> query = queryParams(url);

        mockMvc.perform(put(pathOf(url))
                        .param("expires", query.get("expires"))
                        .param("sig", query.get("sig"))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[9]))
                .andExpect(status().isPayloadTooLarge());
        assertThat(tempDir.resolve(init.uploadId()).resolve("part-1")).doesNotExist();
    }

    @Test
    void putStoresPartAndReturnsQuotedEtag() throws Exception {
        ObjectStorage.MultipartInit init = storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);
        String url = storage.presignPart(OBJECT_KEY, init.uploadId(), 1, TTL);
        Map<String, String> query = queryParams(url);

        mockMvc.perform(put(pathOf(url))
                        .param("expires", query.get("expires"))
                        .param("sig", query.get("sig"))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(utf8("hello ")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG,
                        "\"f814893777bcc2295fff05f00e508da6\""));

        assertThat(storage.listParts(OBJECT_KEY, init.uploadId())).hasSize(1);
    }

    @Test
    void getFullObjectReturns200WithAcceptRanges() throws Exception {
        completedObject();
        String url = storage.presignGet(OBJECT_KEY, TTL);
        Map<String, String> query = queryParams(url);

        mockMvc.perform(get(pathOf(url))
                        .param("expires", query.get("expires"))
                        .param("sig", query.get("sig")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(content().contentType(MediaType.parseMediaType(CONTENT_TYPE)))
                .andExpect(content().string("hello world"));
    }

    @Test
    void getRangeReturns206() throws Exception {
        completedObject();
        String url = storage.presignGet(OBJECT_KEY, TTL);
        Map<String, String> query = queryParams(url);

        mockMvc.perform(get(pathOf(url))
                        .param("expires", query.get("expires"))
                        .param("sig", query.get("sig"))
                        .header(HttpHeaders.RANGE, "bytes=0-4"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-4/11"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "5"))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(content().string("hello"));
    }

    @Test
    void getInvalidRangeReturns416() throws Exception {
        completedObject();
        String url = storage.presignGet(OBJECT_KEY, TTL);
        Map<String, String> query = queryParams(url);

        mockMvc.perform(get(pathOf(url))
                        .param("expires", query.get("expires"))
                        .param("sig", query.get("sig"))
                        .header(HttpHeaders.RANGE, "bytes=99-100"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */11"));
    }

    @Test
    void getMissingObjectReturns404() throws Exception {
        completedObject();
        String url = storage.presignGet(OBJECT_KEY, TTL);
        Map<String, String> query = queryParams(url);
        storage.delete(OBJECT_KEY);

        mockMvc.perform(get(pathOf(url))
                        .param("expires", query.get("expires"))
                        .param("sig", query.get("sig")))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingRequestParametersReturn400() throws Exception {
        mockMvc.perform(get("/files/local/objects/{uploadId}", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
    }

    private ObjectStorage.MultipartInit completedObject() {
        ObjectStorage.MultipartInit init = storage.initMultipart(OBJECT_KEY, CONTENT_TYPE);
        storage.writePart(init.uploadId(), 1, utf8("hello "));
        storage.writePart(init.uploadId(), 2, utf8("world"));
        List<ObjectStorage.UploadedPart> parts =
                storage.listParts(OBJECT_KEY, init.uploadId());
        storage.completeMultipart(OBJECT_KEY, init.uploadId(),
                parts.stream()
                        .map(part -> new ObjectStorage.PartETag(
                                part.partNumber(), part.etag()))
                        .toList());
        return init;
    }

    private static String pathOf(String url) {
        return URI.create(url).getPath();
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
}
