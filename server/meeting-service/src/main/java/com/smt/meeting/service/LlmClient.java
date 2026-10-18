package com.smt.meeting.service;

/**
 * LLM 客户端抽象：只需要"给定 system/user prompt，返回 JSON 字符串"。
 *
 * <p>实现类不负责重试与状态迁移——这些由 {@link MinutesAiService} 统一处理，
 * 从而让 DeepSeek 与 Mock 两种 provider 的行为保持一致。</p>
 */
public interface LlmClient {

    /**
     * 让模型输出 JSON 字符串。
     *
     * @throws RuntimeException 网络/超时/供应商错误；调用方统一转 70009 并记录可读错误
     */
    String completeJson(String systemPrompt, String userPrompt);
}
