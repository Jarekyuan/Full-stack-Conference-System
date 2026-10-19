package com.smt.meeting.config;

import com.smt.meeting.service.AsrClient;
import com.smt.meeting.service.MockAsrClient;
import com.smt.meeting.service.TencentAsrClient;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ASR 客户端装配：tencent / mock 二选一。
 * 默认 tencent；缺密钥时启动只告警，真正请求时返回 70012（避免本地没配密钥就起不来）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AsrConfig {

    private final AsrProperties properties;

    @Bean
    @ConditionalOnProperty(name = "minutes.asr.provider", havingValue = "tencent", matchIfMissing = true)
    public AsrClient tencentAsrClient() {
        if (properties.getSecretId() == null || properties.getSecretId().isBlank()
                || properties.getSecretKey() == null || properties.getSecretKey().isBlank()) {
            log.warn("minutes.asr.provider=tencent 但未配置 TENCENT_ASR_SECRET_ID/KEY；"
                    + "转写请求将返回 70012");
        }
        Credential credential = new Credential(
                properties.getSecretId(), properties.getSecretKey());
        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint(properties.getEndpoint());
        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);
        com.tencentcloudapi.asr.v20190614.AsrClient sdk =
                new com.tencentcloudapi.asr.v20190614.AsrClient(
                        credential, properties.getRegion(), clientProfile);
        return new TencentAsrClient(sdk, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "minutes.asr.provider", havingValue = "mock")
    public AsrClient mockAsrClient() {
        log.warn("minutes.asr.provider=mock：使用本地 ASR 假实现，不调用腾讯云");
        return new MockAsrClient(properties);
    }
}
