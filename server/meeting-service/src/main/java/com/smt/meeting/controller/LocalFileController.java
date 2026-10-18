package com.smt.meeting.controller;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.config.MinutesProperties;
import com.smt.meeting.service.LocalObjectStorage;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地存储的预签名上传/下载端点（仅 {@code storage.provider=local}）。
 *
 * <p>该端点由前端直连 meeting-service（:8082），不经过网关 JWT；HMAC 预签名 token 即鉴权。
 * PUT 返回 403/404/409/413，GET 支持完整下载与单段 Range（206/416）。</p>
 */
@RestController
@RequestMapping("/files/local")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.provider", havingValue = "local")
@CrossOrigin(
        origins = "http://localhost:5173",
        methods = {RequestMethod.PUT, RequestMethod.GET, RequestMethod.OPTIONS},
        exposedHeaders = "ETag")
public class LocalFileController {

    private final LocalObjectStorage storage;
    private final MinutesProperties minutesProperties;

    @PutMapping("/parts/{uploadId}/{partNumber}")
    public ResponseEntity<Void> putPart(@PathVariable String uploadId,
                                        @PathVariable int partNumber,
                                        @RequestParam long expires,
                                        @RequestParam String sig,
                                        HttpServletRequest request,
                                        @RequestBody byte[] body) {
        if (!storage.verifyToken("PUT", uploadId, partNumber, expires, sig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        long maxBytes = minutesProperties.getUpload().getChunkSizeBytes();
        if (request.getContentLengthLong() > maxBytes || body.length > maxBytes) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        String etag = storage.writePart(uploadId, partNumber, body);
        return ResponseEntity.ok().eTag("\"" + etag + "\"").build();
    }

    @GetMapping("/objects/{uploadId}")
    public ResponseEntity<Resource> getObject(@PathVariable String uploadId,
                                              @RequestParam long expires,
                                              @RequestParam String sig,
                                              @RequestHeader(value = HttpHeaders.RANGE,
                                                      required = false) String range) {
        if (!storage.verifyToken("GET", uploadId, null, expires, sig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Path object = storage.objectPath(uploadId);
        MediaType mediaType = mediaType(uploadId);
        long size;
        try {
            size = Files.size(object);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (range == null || range.isBlank()) {
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(size)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(new FileSystemResource(object));
        }
        return rangeResponse(object, size, mediaType, range);
    }

    private ResponseEntity<Resource> rangeResponse(Path object, long size,
                                                   MediaType mediaType, String range) {
        long[] bounds = parseRange(range, size);
        if (bounds == null) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
                    .build();
        }
        long start = bounds[0];
        long end = bounds[1];
        long length = end - start + 1;
        try {
            InputStream input = Files.newInputStream(object);
            input.skipNBytes(start);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(mediaType)
                    .contentLength(length)
                    .header(HttpHeaders.CONTENT_RANGE,
                            "bytes " + start + "-" + end + "/" + size)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(new SlicedResource(new LimitedInputStream(input, length), length));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 解析单段 {@code Range: bytes=start-end}（支持 {@code start-} 与 {@code -suffix}）。
     *
     * @return {@code [start, end]}；语法非法或越界返回 null（调用方回 416）
     */
    private static long[] parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=") || size <= 0) {
            return null;
        }
        String value = header.substring("bytes=".length()).trim();
        if (value.isEmpty() || value.contains(",")) {
            return null;
        }
        int dash = value.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            long start;
            long end;
            if (dash == 0) {
                long suffix = Long.parseLong(value.substring(1));
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(value.substring(0, dash));
                String endPart = value.substring(dash + 1);
                end = endPart.isEmpty() ? size - 1 : Long.parseLong(endPart);
            }
            if (start < 0 || start >= size || end < start) {
                return null;
            }
            return new long[]{start, Math.min(end, size - 1)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private MediaType mediaType(String uploadId) {
        try {
            return MediaType.parseMediaType(storage.contentType(uploadId));
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Void> handleBusiness(BusinessException e) {
        if (e.getCode() == ErrorCode.UPLOAD_SESSION_NOT_FOUND.getCode()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (e.getCode() == ErrorCode.UPLOAD_INCOMPLETE.getCode()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        if (e.getCode() == ErrorCode.PARAM_ERROR.getCode()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Void> handleIo(UncheckedIOException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<Void> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().build();
    }

    /** 只读取 {@code length} 字节，避免把整个对象或 Range 之外的数据写进响应。 */
    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining;

        private LimitedInputStream(InputStream input, long limit) {
            super(input);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = super.read();
            if (value != -1) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }

    private static final class SlicedResource extends InputStreamResource {

        private final long length;

        private SlicedResource(InputStream inputStream, long length) {
            super(inputStream);
            this.length = length;
        }

        @Override
        public long contentLength() {
            return length;
        }
    }
}
