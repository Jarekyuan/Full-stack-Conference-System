package com.smt.meeting.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.region.Region;
import com.smt.meeting.service.CosObjectStorage;
import com.smt.meeting.service.ObjectStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储配置：只保留腾讯云 COS 一条路径。
 *
 * <p>业务层依赖 {@link ObjectStorage} 接口；需要换存储实现时新增实现类即可，
 * 不在配置里保留多套 provider 分支。</p>
 */
@Configuration
public class StorageConfig {

    @Bean(destroyMethod = "shutdown")
    public COSClient cosClient(CosProperties properties) {
        COSCredentials credentials = new BasicCOSCredentials(
                properties.getSecretId(), properties.getSecretKey());
        ClientConfig config = new ClientConfig(new Region(properties.getRegion()));
        return new COSClient(credentials, config);
    }

    @Bean
    public ObjectStorage objectStorage(COSClient client, CosProperties properties) {
        return new CosObjectStorage(client, properties);
    }
}
