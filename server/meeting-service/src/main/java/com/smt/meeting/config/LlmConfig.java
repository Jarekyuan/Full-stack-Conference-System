package com.smt.meeting.config;

import com.smt.meeting.service.DeepSeekLlmClient;
import com.smt.meeting.service.LlmClient;
import com.smt.meeting.service.MockLlmClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * LLM 客户端装配：按 {@code minutes.ai.provider} 二选一。
 *
 * <p>provider 缺省视为 deepseek（与 {@link MinutesProperties.Ai} 的默认值一致）。
 * mock 模式不需要任何密钥，用于本地演示与失败注入。</p>
 */
@Configuration
public class LlmConfig {

    @Bean
    @ConditionalOnProperty(name = "minutes.ai.provider", havingValue = "deepseek", matchIfMissing = true)
    public RestClient deepSeekRestClient(MinutesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(properties.getAi().getTimeoutMs());
        return RestClient.builder()
                .baseUrl(properties.getAi().getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "minutes.ai.provider", havingValue = "deepseek", matchIfMissing = true)
    public LlmClient deepSeekLlmClient(RestClient deepSeekRestClient,
                                       MinutesProperties properties) {
        return new DeepSeekLlmClient(deepSeekRestClient, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "minutes.ai.provider", havingValue = "mock")
    public LlmClient mockLlmClient() {
        return new MockLlmClient();
    }
}
