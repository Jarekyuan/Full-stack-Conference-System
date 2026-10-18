package com.smt.meeting.service;

/**
 * 本地/联调用的假 LLM：返回固定合法 JSON，便于在没有 DeepSeek 密钥时演示完整异步链路。
 *
 * <p>userPrompt 含 {@code MOCK_FAIL} 时主动抛错，用于验证重试与失败状态迁移。</p>
 */
public class MockLlmClient implements LlmClient {

    @Override
    public String completeJson(String systemPrompt, String userPrompt) {
        if (userPrompt.contains("MOCK_FAIL")) {
            throw new IllegalStateException("mock ai failure");
        }
        return """
                {
                  "summary": "本次会议围绕产品排期与压测计划展开，确认了方案 B 并安排了后续动作。",
                  "keyPoints": ["采用方案 B", "下周完成压测"],
                  "decisions": [
                    { "content": "采用方案 B", "context": "对比成本后" }
                  ],
                  "actionItems": [
                    { "task": "补充压测报告", "owner": "张三", "dueDate": "2026-10-25", "priority": "HIGH" }
                  ],
                  "risks": ["第三方接口尚未联调"]
                }
                """;
    }
}
