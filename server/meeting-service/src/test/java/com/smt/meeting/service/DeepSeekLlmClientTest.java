package com.smt.meeting.service;

import com.smt.meeting.config.MinutesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * DeepSeek 客户端：请求形状（协议/鉴权/JSON 约束）与响应解析边界。
 *
 * <p>异常信息不得包含 apiKey 或 prompt——供应商错误可能回显请求信息，
 * 上层会把它转成固定安全文案，但客户端自身也不能把它们拼进异常。</p>
 */
class DeepSeekLlmClientTest {

    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String API_KEY = "sk-test-secret";

    private MinutesProperties properties;
    private MockRestServiceServer server;
    private DeepSeekLlmClient client;

    @BeforeEach
    void setUp() {
        properties = new MinutesProperties();
        properties.getAi().setApiKey(API_KEY);

        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DeepSeekLlmClient(builder.build(), properties);
    }

    @Test
    void postsChatCompletionAndReturnsContent() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(content().json("""
                        {"model":"deepseek-chat","temperature":0.2,
                         "response_format":{"type":"json_object"}}
                        """, false))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"plain-content"}}]}
                        """, MediaType.APPLICATION_JSON));

        String content = client.completeJson("system-prompt", "user-prompt");

        assertThat(content).isEqualTo("plain-content");
        server.verify();
    }

    @Test
    void missingChoicesThrowsWithoutLeakingKeyOrPrompt() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.completeJson("system-prompt", "user-prompt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("choices")
                .hasMessageNotContaining(API_KEY)
                .hasMessageNotContaining("system-prompt")
                .hasMessageNotContaining("user-prompt");
    }

    @Test
    void emptyChoicesThrows() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.completeJson("system", "user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("choices");
    }

    @Test
    void missingContentThrowsWithoutLeakingPrompt() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.completeJson("system-prompt", "user-prompt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content")
                .hasMessageNotContaining("system-prompt")
                .hasMessageNotContaining("user-prompt");
    }

    @Test
    void blankContentThrows() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"   "}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.completeJson("system", "user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content");
    }
}
