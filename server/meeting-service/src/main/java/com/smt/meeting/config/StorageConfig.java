package com.smt.meeting.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.region.Region;
import com.smt.meeting.service.CosObjectStorage;
import com.smt.meeting.service.LocalObjectStorage;
import com.smt.meeting.service.ObjectStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "storage.provider", havingValue = "cos", matchIfMissing = true)
    public COSClient cosClient(CosProperties properties) {
        COSCredentials credentials = new BasicCOSCredentials(
                properties.getSecretId(), properties.getSecretKey());
        ClientConfig config = new ClientConfig(new Region(properties.getRegion()));
        return new COSClient(credentials, config);
    }

    @Bean
    @ConditionalOnProperty(name = "storage.provider", havingValue = "cos", matchIfMissing = true)
    public ObjectStorage cosObjectStorage(COSClient client, CosProperties properties) {
        return new CosObjectStorage(client, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "storage.provider", havingValue = "local")
    public LocalObjectStorage localObjectStorage(StorageProperties properties) {
        return new LocalObjectStorage(properties);
    }
}
