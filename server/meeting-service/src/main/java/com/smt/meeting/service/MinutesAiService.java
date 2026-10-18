package com.smt.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.aop.Log;
import com.smt.meeting.config.MinutesProperties;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.MeetingMinutes;
import com.smt.meeting.entity.MinutesSpeaker;
import com.smt.meeting.entity.TranscriptSegment;
import com.smt.meeting.enums.MinutesAiStatus;
import com.smt.meeting.mapper.MeetingMinutesMapper;
import com.smt.meeting.mapper.MinutesSpeakerMapper;
import com.smt.meeting.mapper.TranscriptSegmentMapper;
import com.smt.meeting.statemachine.MinutesAiStateMachine;
import com.smt.meeting.vo.AiGenerateVO;
import com.smt.meeting.vo.MinutesSummaryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 纪要 AI 生成服务（Task 9）：请求入队、异步生成、失败重试与卡死恢复。
 *
 * <h3>状态与并发</h3>
 * <p>库内状态是唯一事实源：{@code 0/10/20/30/40}。所有迁移先过
 * {@link MinutesAiStateMachine#assertTransition}，再执行带前态条件的 CAS UPDATE：
 * 影响 0 行说明状态已被并发变更（另一个 worker、重新导入或用户重新生成），此时安静退出，
 * <b>绝不把"结果被丢弃"误报成异常</b>。典型的 CAS 0 场景是重新导入的 {@code resetAi} 把
 * 生成中的 20 重置为 0，旧 worker 的 {@code markSuccess} 便应以 0 行收场。</p>
 *
 * <h3>错误可读性</h3>
 * <p>{@code ai_error} 会直接返回给前端，因此只写固定语义或业务异常消息；
 * 供应商原始响应（可能回显请求头/密钥）与堆栈一律不落库、不写日志。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinutesAiService {

    private static final String STUCK_ERROR = "生成超时，请重试";
    /** 非业务异常统一对外文案：不透传供应商响应体，避免密钥/转写内容进入 ai_error。 */
    private static final String LLM_ERROR = "AI 服务超时或不可用，请重试";
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final MeetingMinutesMapper minutesMapper;
    private final MinutesSpeakerMapper speakerMapper;
    private final TranscriptSegmentMapper segmentMapper;
    private final MinutesPermissionService permissionService;
    private final MinutesAiStateMachine stateMachine;
    private final MinutesSummaryParser summaryParser;
    private final MinutesPromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final MinutesProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 请求生成：校验权限/转写/配置后把 0/30/40 置为 10（待生成）。
     *
     * <p>已在 10/20 时幂等返回当前状态，不重复入队；CAS 失败说明状态刚被并发改变，
     * 重读最新状态返回，避免给前端一个过期的"已入队"。</p>
     */
    @Log(type = "MINUTES_GENERATE")
    public AiGenerateVO request(Long meetingId, Long userId) {
        Meeting meeting = permissionService.requireMeeting(meetingId);
        permissionService.assertCanManage(meeting, userId);
        MeetingMinutes minutes = minutesMapper.selectByMeetingId(meetingId);
        if (minutes == null) {
            throw new BusinessException(ErrorCode.MINUTES_NOT_FOUND);
        }
        if (segmentMapper.countByMinutesId(minutes.getId()) <= 0) {
            throw new BusinessException(ErrorCode.TRANSCRIPT_EMPTY);
        }
        assertConfigured();

        int current = statusOf(minutes);
        if (current == MinutesAiStatus.PENDING.getCode()
                || current == MinutesAiStatus.RUNNING.getCode()) {
            return new AiGenerateVO(current);
        }

        stateMachine.assertTransition(current, MinutesAiStatus.PENDING.getCode());
        int updated = minutesMapper.requestGenerate(minutes.getId());
        if (updated == 0) {
            // 与 worker 的 markRunning 或并发生成请求竞争失败：以库里的最新状态为准。
            MeetingMinutes latest = minutesMapper.selectById(minutes.getId());
            return new AiGenerateVO(latest == null
                    ? MinutesAiStatus.PENDING.getCode() : statusOf(latest));
        }
        return new AiGenerateVO(MinutesAiStatus.PENDING.getCode());
    }

    /**
     * 处理一条待生成纪要（由 {@link com.smt.meeting.task.MinutesAiTask} 在条目锁内调用）。
     *
     * <p>流程：10 → 20（CAS）→ 调 LLM → 校验/序列化 → 20 → 30（CAS）。
     * 任一步失败按剩余次数回 10（重试）或 40（终止）。本方法不向调度线程抛异常。</p>
     */
    public void process(Long minutesId) {
        MeetingMinutes minutes = minutesMapper.selectById(minutesId);
        if (minutes == null || statusOf(minutes) != MinutesAiStatus.PENDING.getCode()) {
            return;
        }

        stateMachine.assertTransition(
                MinutesAiStatus.PENDING.getCode(), MinutesAiStatus.RUNNING.getCode());
        if (minutesMapper.markRunning(minutesId, LocalDateTime.now()) == 0) {
            // 另一实例/本轮已被处理，或重新导入把状态重置了。
            log.debug("[纪要 AI] markRunning 未命中，跳过 minutesId={}", minutesId);
            return;
        }

        try {
            List<MinutesSpeaker> speakers = nonNull(speakerMapper.selectByMinutesId(minutesId));
            List<TranscriptSegment> segments = nonNull(segmentMapper.selectByMinutesId(minutesId));
            if (segments.isEmpty()) {
                // 导入与生成之间转写被清空：按失败处理，避免把空内容发给模型。
                throw new BusinessException(ErrorCode.TRANSCRIPT_EMPTY);
            }

            String raw = callLlm(segments, speakers);
            MinutesSummaryVO summary = summaryParser.parseAndValidate(raw);
            String json = objectMapper.writeValueAsString(summary);

            stateMachine.assertTransition(
                    MinutesAiStatus.RUNNING.getCode(), MinutesAiStatus.SUCCESS.getCode());
            int updated = minutesMapper.markSuccess(minutesId, json,
                    properties.getAi().getModel(), LocalDateTime.now());
            if (updated == 0) {
                // 重新导入/重新生成已把状态改走：本次结果有意丢弃（resetAi 的语义）。
                log.warn("[纪要 AI] markSuccess CAS 未命中，结果被丢弃 "
                        + "(discarded: re-import/regenerate) minutesId={}", minutesId);
            }
        } catch (Exception e) {
            handleFailure(minutesId, attemptsOf(minutes), e);
        }
    }

    /**
     * 卡死恢复：20 超过 {@code stuck-timeout-minutes} 仍未收敛时，按一次失败处理。
     *
     * <p>常见于进程在调用 LLM 期间被杀死：没有代码会再执行 markSuccess/markRetry，
     * 只能由下一次扫描把行从 20 拉出来。</p>
     */
    public void handleStuck(Long minutesId) {
        MeetingMinutes minutes = minutesMapper.selectById(minutesId);
        if (minutes == null || statusOf(minutes) != MinutesAiStatus.RUNNING.getCode()) {
            return;
        }
        int attempts = attemptsOf(minutes) + 1;
        int maxAttempts = maxAttempts();
        try {
            if (attempts >= maxAttempts) {
                stateMachine.assertTransition(
                        MinutesAiStatus.RUNNING.getCode(), MinutesAiStatus.FAILED.getCode());
                int updated = minutesMapper.markFailed(
                        minutesId, STUCK_ERROR, LocalDateTime.now(), attempts);
                if (updated == 0) {
                    log.warn("[纪要 AI] markFailed CAS 未命中 (discarded: re-import/regenerate) "
                            + "minutesId={}", minutesId);
                }
                log.warn("[纪要 AI] 生成超时终止 minutesId={} attempts={}", minutesId, attempts);
            } else {
                stateMachine.assertTransition(
                        MinutesAiStatus.RUNNING.getCode(), MinutesAiStatus.PENDING.getCode());
                int updated = minutesMapper.markRetry(
                        minutesId, STUCK_ERROR, LocalDateTime.now(), attempts);
                if (updated == 0) {
                    log.warn("[纪要 AI] markRetry CAS 未命中 (discarded: re-import/regenerate) "
                            + "minutesId={}", minutesId);
                }
                log.warn("[纪要 AI] 生成超时，安排重试 minutesId={} attempts={}", minutesId, attempts);
            }
        } catch (Exception e) {
            // 回写失败不能让调度线程死掉；行仍是 20，下一轮扫描会再次命中。
            log.error("[纪要 AI] 超时状态回写异常 minutesId={}", minutesId, e);
        }
    }

    /** 失败收尾：按 attempts 决定 20 → 10（重试）或 20 → 40（终止）。 */
    private void handleFailure(Long minutesId, int currentAttempts, Exception cause) {
        int attempts = currentAttempts + 1;
        int maxAttempts = maxAttempts();
        String error = readableError(cause);
        boolean finalFailure = attempts >= maxAttempts;
        try {
            if (finalFailure) {
                stateMachine.assertTransition(
                        MinutesAiStatus.RUNNING.getCode(), MinutesAiStatus.FAILED.getCode());
                int updated = minutesMapper.markFailed(
                        minutesId, error, LocalDateTime.now(), attempts);
                if (updated == 0) {
                    log.warn("[纪要 AI] markFailed CAS 未命中 (discarded: re-import/regenerate) "
                            + "minutesId={}", minutesId);
                }
            } else {
                stateMachine.assertTransition(
                        MinutesAiStatus.RUNNING.getCode(), MinutesAiStatus.PENDING.getCode());
                int updated = minutesMapper.markRetry(
                        minutesId, error, LocalDateTime.now(), attempts);
                if (updated == 0) {
                    log.warn("[纪要 AI] markRetry CAS 未命中 (discarded: re-import/regenerate) "
                            + "minutesId={}", minutesId);
                }
            }
            log.warn("[纪要 AI] 生成失败 minutesId={} attempts={} final={} error={}",
                    minutesId, attempts, finalFailure, error);
        } catch (Exception e) {
            // 回写异常同样不向调度线程抛出；行仍是 20，交给卡死扫描恢复。
            log.error("[纪要 AI] 失败状态回写异常 minutesId={}", minutesId, e);
        }
    }

    /** 单块直接生成；多块先逐块压缩要点，再合并生成最终 JSON。 */
    private String callLlm(List<TranscriptSegment> segments,
                           List<MinutesSpeaker> speakers) {
        List<String> chunks = promptBuilder.chunks(segments, speakers);
        if (chunks.size() <= 1) {
            return llmClient.completeJson(MinutesPromptBuilder.SYSTEM_PROMPT,
                    promptBuilder.fullPrompt(segments, speakers));
        }
        List<String> notes = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            notes.add(llmClient.completeJson(
                    MinutesPromptBuilder.CHUNK_SYSTEM_PROMPT, chunk));
        }
        return llmClient.completeJson(MinutesPromptBuilder.SYSTEM_PROMPT,
                promptBuilder.mergePrompt(notes));
    }

    /** 开关关闭或 deepseek 模式缺密钥 → 70008；mock 模式永远可用。 */
    private void assertConfigured() {
        MinutesProperties.Ai ai = properties.getAi();
        if (!Boolean.TRUE.equals(ai.getEnabled())) {
            throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED);
        }
        if ("mock".equalsIgnoreCase(ai.getProvider())) {
            return;
        }
        if (ai.getApiKey() == null || ai.getApiKey().isBlank()) {
            throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED);
        }
    }

    /**
     * 只返回可安全展示的错误。非业务异常（RestClient 超时/4xx/5xx）的 message 可能包含
     * 供应商响应体甚至请求头，不能透传。
     */
    private String readableError(Exception e) {
        if (e instanceof BusinessException businessException
                && businessException.getMessage() != null
                && !businessException.getMessage().isBlank()) {
            return businessException.getMessage();
        }
        return LLM_ERROR;
    }

    private int statusOf(MeetingMinutes minutes) {
        return minutes.getAiStatus() == null
                ? MinutesAiStatus.NOT_GENERATED.getCode() : minutes.getAiStatus();
    }

    private int attemptsOf(MeetingMinutes minutes) {
        return minutes.getAiAttempts() == null ? 0 : minutes.getAiAttempts();
    }

    private int maxAttempts() {
        Integer configured = properties.getAi().getMaxAttempts();
        return configured == null || configured <= 0 ? DEFAULT_MAX_ATTEMPTS : configured;
    }

    private static <T> List<T> nonNull(List<T> values) {
        return values == null ? List.of() : values;
    }
}
