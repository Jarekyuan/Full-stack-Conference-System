package com.smt.meeting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    private String provider = "cos";
    private Local local = new Local();

    @Data
    public static class Local {
        private String dir = "./data/minutes";
        private String baseUrl = "http://127.0.0.1:8082";
    }
}
