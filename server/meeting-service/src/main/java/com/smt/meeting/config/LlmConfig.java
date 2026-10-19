package com.smt.meeting.config;

import com.smt.meeting.service.DeepSeekLlmClient;
import com.smt.meeting.service.LlmClient;
import com.smt.meeting.service.MockLlmClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *
 * <p>启动时校验 {@code minutes.ai.timeout-ms >= 1}：0/负数传给
 * {@link SimpleClientHttpRequestFactory#setReadTimeout(int)} 会退化成"永不超时"，
 * 一次挂死的供应商调用就会长期占住 worker。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class LlmConfig {

    private final MinutesProperties properties;

    @PostConstruct
    void validateTimeouts() {
        Integer timeoutMs = properties.getAi().getTimeoutMs();
        if (timeoutMs == null || timeoutMs < 1) {
            throw new IllegalStateException(
                    "minutes.ai.timeout-ms 必须 >= 1（当前: " + timeoutMs + "）");
        }
    }

    @PostConstruct
    void warnMissingKey() {
        MinutesProperties.Ai ai = properties.getAi();
        if ("mock".equalsIgnoreCase(ai.getProvider())) {
            return;
        }
        if (ai.getApiKey() == null || ai.getApiKey().isBlank()) {
            log.warn("minutes.ai.provider={} 但未配置 DEEPSEEK_API_KEY；"
                    + "触发 AI 纪要将返回 70008", ai.getProvider());
        }
    }

    @Bean
    @ConditionalOnProperty(name = "minutes.ai.provider", havingValue = "deepseek", matchIfMissing = true)
    public RestClient deepSeekRestClient() {
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
    public LlmClient deepSeekLlmClient(RestClient deepSeekRestClient) {
        return new DeepSeekLlmClient(deepSeekRestClient, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "minutes.ai.provider", havingValue = "mock")
    public LlmClient mockLlmClient() {
        // 启动日志明确提示：避免把本地抽取式演示误认为 DeepSeek 生成异常。
        log.warn("minutes.ai.provider=mock：AI 纪要为本地抽取式演示，未调用真实大模型");
        return new MockLlmClient();
    }
}
