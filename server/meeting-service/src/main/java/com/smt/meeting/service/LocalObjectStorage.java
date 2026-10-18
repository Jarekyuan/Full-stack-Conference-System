package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.config.StorageProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public class LocalObjectStorage implements ObjectStorage {

    private final Path root;
    private final String baseUrl;
    private final byte[] secret = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);

    public LocalObjectStorage(StorageProperties properties) {
        this.root = Paths.get(properties.getLocal().getDir()).toAbsolutePath().normalize();
        this.baseUrl = properties.getLocal().getBaseUrl();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建本地对象目录", e);
        }
    }

    @Override
    public MultipartInit initMultipart(String objectKey, String contentType) {
        String uploadId = UUID.randomUUID().toString();
        Path dir = dir(uploadId);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("content-type.txt"), contentType);
            Files.writeString(dir.resolve("object-key.txt"), objectKey);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建本地分片会话", e);
        }
        return new MultipartInit(uploadId);
    }

    @Override
    public List<UploadedPart> listParts(String objectKey, String uploadId) {
        Path dir = dir(uploadId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<UploadedPart> parts = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(path -> path.getFileName().toString().startsWith("part-"))
                    .sorted(Comparator.comparingInt(path -> partNumber(path.getFileName().toString())))
                    .forEach(path -> {
                        try {
                            parts.add(new UploadedPart(
                                    partNumber(path.getFileName().toString()),
                                    md5(path),
                                    Files.size(path)));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("读取本地分片失败", e);
        }
        return parts;
    }

    @Override
    public String presignPart(String objectKey, String uploadId, int partNumber, Duration ttl) {
        return signedUrl("PUT", uploadId, partNumber, ttl);
    }

    @Override
    public StoredObject completeMultipart(String objectKey, String uploadId, List<PartETag> parts) {
        Path dir = dir(uploadId);
        List<UploadedPart> uploaded = listParts(objectKey, uploadId);
        if (uploaded.size() != parts.size()) {
            throw new BusinessException(ErrorCode.UPLOAD_INCOMPLETE);
        }
        try {
            Path object = dir.resolve("object.bin");
            Files.deleteIfExists(object);
            try (var output = Files.newOutputStream(object)) {
                for (int i = 1; i <= uploaded.size(); i++) {
                    Path part = dir.resolve("part-" + i);
                    if (!Files.exists(part)) {
                        throw new BusinessException(ErrorCode.UPLOAD_INCOMPLETE);
                    }
                    Files.copy(part, output);
                }
            }
            return head(objectKey);
        } catch (IOException e) {
            throw new UncheckedIOException("合并本地分片失败", e);
        }
    }

    @Override
    public void abortMultipart(String objectKey, String uploadId) {
        deleteDirectory(dir(uploadId));
    }

    @Override
    public StoredObject head(String objectKey) {
        for (Path dir : sessionDirs()) {
            Path keyFile = dir.resolve("object-key.txt");
            Path object = dir.resolve("object.bin");
            try {
                if (Files.exists(keyFile) && Files.exists(object)
                        && Files.readString(keyFile).equals(objectKey)) {
                    return new StoredObject(objectKey, Files.size(object), contentType(dir));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
    }

    @Override
    public String presignGet(String objectKey, Duration ttl) {
        String uploadId = findUploadId(objectKey);
        return signedUrl("GET", uploadId, null, ttl);
    }

    @Override
    public void delete(String objectKey) {
        String uploadId = findUploadId(objectKey);
        deleteDirectory(dir(uploadId));
    }

    /** 仅供 LocalFileController 使用：写入分片并返回 ETag（MD5）。 */
    public String writePart(String uploadId, int partNumber, byte[] body) {
        Path dir = dir(uploadId);
        if (!Files.isDirectory(dir)) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        try {
            Path part = dir.resolve("part-" + partNumber);
            Files.write(part, body);
            return md5(part);
        } catch (IOException e) {
            throw new UncheckedIOException("写入本地分片失败", e);
        }
    }

    /** 仅供 LocalFileController 使用：读取完整对象。 */
    public byte[] readObject(String uploadId) {
        try {
            return Files.readAllBytes(dir(uploadId).resolve("object.bin"));
        } catch (IOException e) {
            throw new UncheckedIOException("读取本地对象失败", e);
        }
    }

    public String contentType(String uploadId) {
        return contentType(dir(uploadId));
    }

    /** 校验预签名 URL 的 HMAC 与过期时间。 */
    public boolean verifyToken(String action, String uploadId, Integer partNumber,
                               long expires, String signature) {
        if (System.currentTimeMillis() > expires) {
            return false;
        }
        String expected = sign(payload(action, uploadId, partNumber, expires));
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private String signedUrl(String action, String uploadId, Integer partNumber, Duration ttl) {
        long expires = System.currentTimeMillis() + ttl.toMillis();
        String path = "PUT".equals(action)
                ? "/files/local/parts/" + uploadId + "/" + partNumber
                : "/files/local/objects/" + uploadId;
        return baseUrl + path + "?expires=" + expires
                + "&sig=" + sign(payload(action, uploadId, partNumber, expires));
    }

    private String payload(String action, String uploadId, Integer partNumber, long expires) {
        return action + ":" + uploadId + ":" + (partNumber == null ? "" : partNumber) + ":" + expires;
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("本地 URL 签名失败", e);
        }
    }

    private String findUploadId(String objectKey) {
        for (Path dir : sessionDirs()) {
            try {
                Path keyFile = dir.resolve("object-key.txt");
                if (Files.exists(keyFile) && Files.readString(keyFile).equals(objectKey)) {
                    return dir.getFileName().toString();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
    }

    private List<Path> sessionDirs() {
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String contentType(Path dir) {
        try {
            Path file = dir.resolve("content-type.txt");
            return Files.exists(file)
                    ? Files.readString(file)
                    : "application/octet-stream";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path dir(String uploadId) {
        if (!uploadId.matches("[0-9a-fA-F-]{36}")) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        return root.resolve(uploadId);
    }

    private int partNumber(String fileName) {
        return Integer.parseInt(fileName.substring("part-".length()));
    }

    private String md5(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (Exception e) {
            throw new IOException("计算 MD5 失败", e);
        }
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("删除本地对象失败", e);
        }
    }
}
