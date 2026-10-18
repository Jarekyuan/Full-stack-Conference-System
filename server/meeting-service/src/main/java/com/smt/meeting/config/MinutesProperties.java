package com.smt.meeting.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "minutes")
public class MinutesProperties {

    private Upload upload = new Upload();
    private Transcript transcript = new Transcript();
    private Ai ai = new Ai();

    @Data
    public static class Upload {
        private Long maxFileSizeBytes = 2L * 1024 * 1024 * 1024;
        private Integer chunkSizeBytes = 5 * 1024 * 1024;
        private Integer sessionTtlHours = 24;
        /** 分片预签名 URL 有效期（分钟）。放在 minutes 下而非 cos 下：本地 provider 同样使用。 */
        private Integer partUrlTtlMinutes = 60;
    }

    @Data
    public static class Transcript {
        private Long maxBytes = 5L * 1024 * 1024;
        private Integer maxSegments = 20_000;
        private Integer maxSegmentChars = 2_000;
    }

    @Data
    public static class Ai {
        private Boolean enabled = true;
        private String provider = "deepseek";
        private String baseUrl = "https://api.deepseek.com";
        private String model = "deepseek-chat";
        @ToString.Exclude
        private String apiKey = "";
        private Integer timeoutMs = 120_000;
        private Integer maxAttempts = 3;
        private Integer maxInputChars = 60_000;
        /** 单次生成允许的最大分块数；超过直接 70009，避免把超长转写拆成大量付费调用。 */
        private Integer maxChunks = 10;
        /** 成功/失败后再次请求生成的最小间隔（秒）；0 表示不限制。 */
        private Integer regenerateCooldownSeconds = 60;
        private Integer stuckTimeoutMinutes = 10;
    }
}
