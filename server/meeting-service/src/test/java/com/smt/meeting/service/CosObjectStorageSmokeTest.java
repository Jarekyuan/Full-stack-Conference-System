package com.smt.meeting.service;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.region.Region;
import com.smt.meeting.config.CosProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 COS 冒烟（手动运行，不进入日常回归）：
 *   export COS_SECRET_ID/COS_SECRET_KEY/COS_REGION/COS_BUCKET
 *   bash scripts/verify-cos-upload.sh
 *
 * 覆盖 init → 预签名分片 PUT → complete → head → delete。对象在 finally 删除。
 */
@EnabledIfEnvironmentVariable(named = "COS_BUCKET", matches = ".+")
class CosObjectStorageSmokeTest {

    @Test
    void multipartRoundTripAgainstRealCos() throws Exception {
        CosProperties properties = new CosProperties();
        properties.setSecretId(System.getenv("COS_SECRET_ID"));
        properties.setSecretKey(System.getenv("COS_SECRET_KEY"));
        properties.setRegion(System.getenv("COS_REGION"));
        properties.setBucket(System.getenv("COS_BUCKET"));

        COSClient client = new COSClient(
                new BasicCOSCredentials(properties.getSecretId(), properties.getSecretKey()),
                new ClientConfig(new Region(properties.getRegion())));
        CosObjectStorage storage = new CosObjectStorage(client, properties);
        String key = "smoke/minutes-" + System.currentTimeMillis() + ".txt";
        ObjectStorage.MultipartInit init = storage.initMultipart(key, "text/plain");
        try {
            byte[] body = "hello cos".getBytes(StandardCharsets.UTF_8);
            String url = storage.presignPart(key, init.uploadId(), 1, Duration.ofMinutes(10));
            HttpResponse<String> put = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(put.statusCode()).isEqualTo(200);
            String etag = put.headers().firstValue("ETag").orElseThrow();

            ObjectStorage.StoredObject object = storage.completeMultipart(
                    key, init.uploadId(),
                    List.of(new ObjectStorage.PartETag(1, etag)));
            assertThat(object.size()).isEqualTo(body.length);
            assertThat(storage.head(key).size()).isEqualTo(body.length);
        } finally {
            storage.delete(key);
            client.shutdown();
        }
    }
}
