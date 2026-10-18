package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.config.StorageProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * 本地文件系统对象存储（开发 / CI / e2e）。
 *
 * <p>目录布局：{@code root/{uploadId}/} 下保存 {@code part-N}、{@code object.bin}、
 * {@code content-type.txt}、{@code object-key.txt}。预签名 URL 的 HMAC 密钥是
 * <b>进程内随机生成</b>的，因此服务重启后旧 URL 全部失效——这是本地实现的有意取舍，
 * COS 的预签名 URL 不受影响。</p>
 */
public class LocalObjectStorage implements ObjectStorage {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern PART_FILE = Pattern.compile("part-\\d+");
    private static final String OBJECT_FILE = "object.bin";
    private static final String CONTENT_TYPE_FILE = "content-type.txt";
    private static final String OBJECT_KEY_FILE = "object-key.txt";
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final Path root;
    private final String baseUrl;
    private final byte[] secret = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
    /**
     * 按 uploadId 串行化 complete：避免两个 complete 并发时交错清理分片。
     * 分配后不回收（uploadId 唯一，本地实现生命周期有限）。
     */
    private final ConcurrentHashMap<String, ReentrantLock> uploadLocks = new ConcurrentHashMap<>();

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
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        String uploadId = UUID.randomUUID().toString();
        Path dir = dir(uploadId);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(CONTENT_TYPE_FILE),
                    contentType == null ? DEFAULT_CONTENT_TYPE : contentType);
            Files.writeString(dir.resolve(OBJECT_KEY_FILE), objectKey);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建本地分片会话", e);
        }
        return new MultipartInit(uploadId);
    }

    @Override
    public List<UploadedPart> listParts(String objectKey, String uploadId) {
        Path dir = dir(uploadId);
        requireActiveSession(dir);
        requireObjectKey(dir, objectKey);
        return listPartsInternal(dir);
    }

    @Override
    public String presignPart(String objectKey, String uploadId, int partNumber, Duration ttl) {
        if (partNumber < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        Path dir = dir(uploadId);
        requireActiveSession(dir);
        requireObjectKey(dir, objectKey);
        return signedUrl("PUT", uploadId, partNumber, ttl);
    }

    @Override
    public StoredObject completeMultipart(String objectKey, String uploadId, List<PartETag> parts) {
        Path dir = dir(uploadId);
        ReentrantLock lock = uploadLocks.computeIfAbsent(uploadId, key -> new ReentrantLock());
        lock.lock();
        try {
            return completeMultipartLocked(dir, objectKey, uploadId, parts);
        } finally {
            lock.unlock();
        }
    }

    private StoredObject completeMultipartLocked(Path dir, String objectKey,
                                                 String uploadId, List<PartETag> parts) {
        // 持锁后重新校验：并发的另一个 complete 可能已经完成本次上传
        requireActiveSession(dir);
        requireObjectKey(dir, objectKey);
        Map<Integer, UploadedPart> uploaded = uploadedByNumber(dir);

        if (parts == null || parts.isEmpty()) {
            throw incomplete();
        }
        Set<Integer> supplied = new HashSet<>();
        for (PartETag part : parts) {
            if (part == null || part.partNumber() < 1 || !supplied.add(part.partNumber())) {
                throw incomplete();
            }
            UploadedPart stored = uploaded.get(part.partNumber());
            if (stored == null
                    || !normalizeEtag(part.etag()).equalsIgnoreCase(stored.etag())) {
                throw incomplete();
            }
        }
        Set<Integer> expected = IntStream.rangeClosed(1, uploaded.size())
                .boxed()
                .collect(Collectors.toSet());
        if (!supplied.equals(uploaded.keySet()) || !supplied.equals(expected)) {
            throw incomplete();
        }

        // 唯一临时名：并发 complete 即使绕过锁也不会写同一个临时文件
        Path temp = dir.resolve(OBJECT_FILE + "." + UUID.randomUUID() + ".tmp");
        Path object = dir.resolve(OBJECT_FILE);
        try {
            try (OutputStream output = Files.newOutputStream(temp)) {
                for (int i = 1; i <= uploaded.size(); i++) {
                    try (InputStream input = Files.newInputStream(dir.resolve("part-" + i))) {
                        input.transferTo(output);
                    }
                }
            }
            Files.move(temp, object,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            for (int i = 1; i <= uploaded.size(); i++) {
                Files.deleteIfExists(dir.resolve("part-" + i));
            }
            return head(objectKey);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("合并本地分片失败", e);
        }
    }

    @Override
    public void abortMultipart(String objectKey, String uploadId) {
        Path dir = dir(uploadId);
        // 会话不存在或已上传完成：幂等空操作，不能误删已完成对象
        if (!Files.isDirectory(dir) || Files.exists(dir.resolve(OBJECT_FILE))) {
            return;
        }
        deleteDirectory(dir);
    }

    @Override
    public StoredObject head(String objectKey) {
        Path dir = findCompletedDir(objectKey);
        if (dir == null) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        try {
            return new StoredObject(objectKey,
                    Files.size(dir.resolve(OBJECT_FILE)), contentType(dir));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String presignGet(String objectKey, Duration ttl) {
        Path dir = findCompletedDir(objectKey);
        if (dir == null) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        return signedUrl("GET", dir.getFileName().toString(), null, ttl);
    }

    @Override
    public void delete(String objectKey) {
        Path dir = findCompletedDir(objectKey);
        if (dir != null) {
            deleteDirectory(dir);
        }
    }

    /** 仅供 LocalFileController 使用：写入分片并返回 ETag（MD5）。 */
    public String writePart(String uploadId, int partNumber, byte[] body) {
        if (body == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        return writePart(uploadId, partNumber, new ByteArrayInputStream(body), body.length);
    }

    /**
     * 流式写入分片：超过 {@code maxBytes} 立即失败（70005）并清理临时文件。
     * 临时文件带随机后缀，同一分片的并发写不会互相混入字节；最终原子替换，last-writer-wins。
     */
    public String writePart(String uploadId, int partNumber, InputStream input, long maxBytes) {
        if (partNumber < 1 || input == null || maxBytes < 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        Path dir = dir(uploadId);
        requireActiveSession(dir);
        Path temp = dir.resolve("part-" + partNumber + "." + UUID.randomUUID() + ".tmp");
        MessageDigest digest = md5Digest();
        long total = 0;
        try (OutputStream output = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (BusinessException e) {
            deleteQuietly(temp);
            throw e;
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("写入本地分片失败", e);
        } catch (RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
        String etag = HexFormat.of().formatHex(digest.digest());
        try {
            Files.move(temp, dir.resolve("part-" + partNumber),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("写入本地分片失败", e);
        }
        return etag;
    }

    /** 仅供 LocalFileController 使用：读取完整对象。 */
    public byte[] readObject(String uploadId) {
        try {
            return Files.readAllBytes(objectPath(uploadId));
        } catch (IOException e) {
            throw new UncheckedIOException("读取本地对象失败", e);
        }
    }

    public String contentType(String uploadId) {
        return contentType(dir(uploadId));
    }

    /** 仅供 LocalFileController 使用：返回已校验的 object.bin 路径。 */
    public Path objectPath(String uploadId) {
        Path object = dir(uploadId).resolve(OBJECT_FILE);
        if (!Files.isRegularFile(object)) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        return object;
    }

    /** 校验预签名 URL 的 HMAC 与过期时间。 */
    public boolean verifyToken(String action, String uploadId, Integer partNumber,
                               long expires, String signature) {
        if (action == null || uploadId == null || signature == null
                || System.currentTimeMillis() > expires) {
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

    private Map<Integer, UploadedPart> uploadedByNumber(Path dir) {
        Map<Integer, UploadedPart> uploaded = new LinkedHashMap<>();
        for (UploadedPart part : listPartsInternal(dir)) {
            uploaded.put(part.partNumber(), part);
        }
        return uploaded;
    }

    private List<UploadedPart> listPartsInternal(Path dir) {
        List<UploadedPart> parts = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(path -> PART_FILE.matcher(path.getFileName().toString()).matches())
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

    private Path findCompletedDir(String objectKey) {
        for (Path dir : sessionDirs()) {
            if (Files.isRegularFile(dir.resolve(OBJECT_FILE))
                    && objectKeyMatches(dir, objectKey)) {
                return dir;
            }
        }
        return null;
    }

    private void requireActiveSession(Path dir) {
        if (!Files.isDirectory(dir) || Files.exists(dir.resolve(OBJECT_FILE))) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
    }

    private void requireObjectKey(Path dir, String objectKey) {
        if (!objectKeyMatches(dir, objectKey)) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
    }

    private boolean objectKeyMatches(Path dir, String objectKey) {
        try {
            Path keyFile = dir.resolve(OBJECT_KEY_FILE);
            return Files.exists(keyFile) && Files.readString(keyFile).equals(objectKey);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
            Path file = dir.resolve(CONTENT_TYPE_FILE);
            return Files.exists(file)
                    ? Files.readString(file)
                    : DEFAULT_CONTENT_TYPE;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path dir(String uploadId) {
        if (uploadId == null) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        try {
            UUID.fromString(uploadId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        if (!UUID_PATTERN.matcher(uploadId).matches()) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        Path resolved = root.resolve(uploadId).normalize();
        if (!resolved.startsWith(root) || !root.equals(resolved.getParent())) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        return resolved;
    }

    private int partNumber(String fileName) {
        return Integer.parseInt(fileName.substring("part-".length()));
    }

    private String md5(Path path) throws IOException {
        MessageDigest digest = md5Digest();
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不覆盖原始异常
        }
    }

    private static String normalizeEtag(String etag) {
        if (etag == null) {
            return "";
        }
        String value = etag.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static BusinessException incomplete() {
        return new BusinessException(ErrorCode.UPLOAD_INCOMPLETE);
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
