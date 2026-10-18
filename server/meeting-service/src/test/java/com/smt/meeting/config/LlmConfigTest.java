package com.smt.meeting.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 启动期配置校验：0/负的 read timeout 会让 RestClient 永不超时，必须在装配阶段拒绝。
 */
class LlmConfigTest {

    @Test
    void rejectsZeroTimeoutAtStartup() {
        MinutesProperties properties = new MinutesProperties();
        properties.getAi().setTimeoutMs(0);

        assertThatThrownBy(() -> new LlmConfig(properties).validateTimeouts())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timeout-ms");
    }

    @Test
    void rejectsNegativeTimeoutAtStartup() {
        MinutesProperties properties = new MinutesProperties();
        properties.getAi().setTimeoutMs(-1);

        assertThatThrownBy(() -> new LlmConfig(properties).validateTimeouts())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timeout-ms");
    }

    @Test
    void acceptsPositiveTimeout() {
        MinutesProperties properties = new MinutesProperties();

        assertThatCode(() -> new LlmConfig(properties).validateTimeouts())
                .doesNotThrowAnyException();
    }
}
