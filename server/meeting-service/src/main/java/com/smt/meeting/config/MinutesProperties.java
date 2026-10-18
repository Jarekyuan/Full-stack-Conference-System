package com.smt.meeting.config;

import lombok.Data;
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
        private String apiKey = "";
        private Integer timeoutMs = 120_000;
        private Integer maxAttempts = 3;
        private Integer maxInputChars = 60_000;
        private Integer scanDelayMs = 5_000;
        private Integer stuckTimeoutMinutes = 10;
    }
}
