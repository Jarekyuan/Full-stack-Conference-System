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
    // 预签名 URL 的 TTL 由调用方传入（分片见 minutes.upload.part-url-ttl-minutes），此处不再重复配置
}
