package com.smt.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mock provider：固定 JSON 必须能通过严格解析；MOCK_FAIL 用于失败注入。
 */
class MockLlmClientTest {

    private final MockLlmClient client = new MockLlmClient();
    private final MinutesSummaryParser parser =
            MinutesSummaryParser.withObjectMapper(new ObjectMapper());

    @Test
    void returnsValidSummaryJson() {
        String json = client.completeJson("system", "会议内容");

        assertThat(parser.parseAndValidate(json).getSummary()).isNotBlank();
    }

    @Test
    void failsWhenPromptContainsFailureMarker() {
        assertThatThrownBy(() -> client.completeJson("system", "MOCK_FAIL"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mock ai failure");
    }
}
