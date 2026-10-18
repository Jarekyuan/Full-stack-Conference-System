package com.smt.meeting.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "cos")
public class CosProperties {

    @ToString.Exclude
    private String secretId = "";
    @ToString.Exclude
    private String secretKey = "";
    private String region = "";
    private String bucket = "";
    private Integer partUrlTtlMinutes = 60;
    private Integer downloadUrlTtlMinutes = 60;
}
