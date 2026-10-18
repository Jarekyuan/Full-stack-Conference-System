package com.smt.meeting.service;

import com.smt.meeting.config.MinutesProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek Chat Completions 客户端（OpenAI 兼容协议）。
 *
 * <p>只用 {@code response_format=json_object} 约束模型输出 JSON；内容是否合法仍由
 * {@link MinutesSummaryParser} 严格校验。</p>
 *
 * <p><b>安全约束</b>：不记录 apiKey、Authorization 头与 prompt 内容。供应商错误响应体
 * 可能回显请求信息，因此异常只携带固定语义（由 {@link MinutesAiService#readableError} 兜底），
 * 不把原始 body 写进日志或 {@code ai_error}。</p>
 */
@RequiredArgsConstructor
public class DeepSeekLlmClient implements LlmClient {

    private final RestClient restClient;
    private final MinutesProperties properties;

    @Override
    public String completeJson(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", properties.getAi().getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0.2);

        Map<?, ?> response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + properties.getAi().getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new IllegalStateException("AI 返回为空");
        }
        Object choicesValue = response.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()) {
            throw new IllegalStateException("AI 返回缺少 choices");
        }
        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> choice)) {
            throw new IllegalStateException("AI 返回 choices 格式错误");
        }
        Object messageValue = choice.get("message");
        if (!(messageValue instanceof Map<?, ?> message)) {
            throw new IllegalStateException("AI 返回缺少 message");
        }
        Object content = message.get("content");
        if (content == null || content.toString().isBlank()) {
            throw new IllegalStateException("AI 返回缺少 content");
        }
        return content.toString();
    }
}
