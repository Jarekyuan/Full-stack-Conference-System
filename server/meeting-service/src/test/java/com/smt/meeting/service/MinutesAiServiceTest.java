package com.smt.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 9：纪要 AI 服务。
 *
 * <p>重点钉住：请求生成的权限/转写/配置前置校验与幂等；CAS 失败后的状态回读；
 * 成功/失败/重试/终止四条路径的迁移与 attempts；CAS 0 行不抛异常；
 * 卡死恢复；错误信息不透传供应商原始内容。</p>
 */
class MinutesAiServiceTest {

    private static final Long MEETING_ID = 42L;
    private static final Long USER_ID = 4L;
    private static final Long MINUTES_ID = 99L;

    private static final String VALID_JSON = """
            {
              "summary": "摘要",
              "keyPoints": ["结论"],
              "decisions": [{"content": "采用 B", "context": null}],
              "actionItems": [{"task": "补报告", "owner": null,
                               "dueDate": "2026-10-25", "priority": "HIGH"}],
              "risks": []
            }
            """;

    private MeetingMinutesMapper minutesMapper;
    private MinutesSpeakerMapper speakerMapper;
    private TranscriptSegmentMapper segmentMapper;
    private MinutesPermissionService permissionService;
    private MinutesAiStateMachine stateMachine;
    private LlmClient llmClient;
    private MinutesProperties properties;
    private MinutesAiService service;

    @BeforeEach
    void setUp() {
        minutesMapper = mock(MeetingMinutesMapper.class);
        speakerMapper = mock(MinutesSpeakerMapper.class);
        segmentMapper = mock(TranscriptSegmentMapper.class);
        permissionService = mock(MinutesPermissionService.class);
        llmClient = mock(LlmClient.class);
        stateMachine = spy(new MinutesAiStateMachine());
        properties = new MinutesProperties();
        // mock provider：request 的配置校验无需真实密钥；LLM 仍用 mock 接口注入
        properties.getAi().setProvider("mock");
        properties.getAi().setMaxAttempts(3);
        service = new MinutesAiService(minutesMapper, speakerMapper, segmentMapper,
                permissionService, stateMachine,
                MinutesSummaryParser.withObjectMapper(new ObjectMapper()),
                new MinutesPromptBuilder(properties), llmClient, properties,
                new ObjectMapper());
    }

    // ---------- 夹具 ----------

    private Meeting meeting() {
        Meeting meeting = new Meeting();
        meeting.setId(MEETING_ID);
        meeting.setOrganiserId(USER_ID);
        meeting.setStatus(40);
        return meeting;
    }

    private MeetingMinutes minutes(int status, int attempts) {
        MeetingMinutes minutes = new MeetingMinutes();
        minutes.setId(MINUTES_ID);
        minutes.setMeetingId(MEETING_ID);
        minutes.setAiStatus(status);
        minutes.setAiAttempts(attempts);
        return minutes;
    }

    private void stubTranscript() {
        MinutesSpeaker speaker = new MinutesSpeaker();
        speaker.setSpeakerKey("0");
        speaker.setDisplayName("张三");
        TranscriptSegment segment = new TranscriptSegment();
        segment.setSpeakerKey("0");
        segment.setStartMs(0L);
        segment.setEndMs(1_000L);
        segment.setText("你好");
        when(speakerMapper.selectByMinutesId(MINUTES_ID)).thenReturn(List.of(speaker));
        when(segmentMapper.selectByMinutesId(MINUTES_ID)).thenReturn(List.of(segment));
    }

    private void stubPendingMinutes(int attempts) {
        when(minutesMapper.selectById(MINUTES_ID)).thenReturn(minutes(10, attempts));
        when(minutesMapper.markRunning(eq(MINUTES_ID), any(LocalDateTime.class))).thenReturn(1);
    }

    private static int codeOf(Throwable throwable) {
        return ((BusinessException) throwable).getCode();
    }

    // ---------- request ----------

    @Test
    void requestRejectsWhenMinutesMissing() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting());
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.request(MEETING_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MinutesAiServiceTest::codeOf)
                .isEqualTo(ErrorCode.MINUTES_NOT_FOUND.getCode());

        verify(segmentMapper, never()).countByMinutesId(anyLong());
        verify(minutesMapper, never()).requestGenerate(anyLong());
    }

    @Test
    void requestRejectsWhenNoTranscript() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting());
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(minutes(0, 0));
        when(segmentMapper.countByMinutesId(MINUTES_ID)).thenReturn(0L);

        assertThatThrownBy(() -> service.request(MEETING_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MinutesAiServiceTest::codeOf)
                .isEqualTo(ErrorCode.TRANSCRIPT_EMPTY.getCode());

        verify(permissionService).assertCanManage(any(Meeting.class), eq(USER_ID));
        verify(minutesMapper, never()).requestGenerate(anyLong());
    }

    @Test
    void requestRejectsWhenDeepSeekKeyMissing() {
        properties.getAi().setProvider("deepseek");
        properties.getAi().setApiKey("");
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting());
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(minutes(0, 0));
        when(segmentMapper.countByMinutesId(MINUTES_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.request(MEETING_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MinutesAiServiceTest::codeOf)
                .isEqualTo(ErrorCode.AI_NOT_CONFIGURED.getCode());

        verify(minutesMapper, never()).requestGenerate(anyLong());
    }

    @Test
    void requestRejectsWhenAiDisabled() {
        properties.getAi().setEnabled(false);
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting());
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(minutes(0, 0));
        when(segmentMapper.countByMinutesId(MINUTES_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.request(MEETING_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(MinutesAiServiceTest::codeOf)
                .isEqualTo(ErrorCode.AI_NOT_CONFIGURED.getCode());

        verify(minutesMapper, never()).requestGenerate(anyLong());
    }

    @Test
    void requestMovesSuccessToPending() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting());
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(minutes(30, 0));
        when(segmentMapper.countByMinutesId(MINUTES_ID)).thenReturn(1L);
        when(minutesMapper.requestGenerate(MINUTES_ID)).thenReturn(1);

        AiGenerateVO result = service.request(MEETING_ID, USER_ID);

        assertThat(result.getAiStatus()).isEqualTo(MinutesAiStatus.PENDING.getCode());
        verify(stateMachine).assertTransition(30, 10);
        verify(minutesMapper).requestGenerate(MINUTES_ID);
    }

    @Test
    void requestIsIdempotentWhilePendingOrRunning() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting());
        when(minutesMapper.selectByMeetingId(MEETING_ID))
                .thenReturn(minutes(10, 0), minutes(20, 0));
        when(segmentMapper.countByMinutesId(MINUTES_ID)).thenReturn(1L);

        assertThat(service.request(MEETING_ID, USER_ID).getAiStatus())
                .isEqualTo(MinutesAiStatus.PENDING.getCode());
        assertThat(service.request(MEETING_ID, USER_ID).getAiStatus())
                .isEqualTo(MinutesAiStatus.RUNNING.getCode());

        verify(minutesMapper, never()).requestGenerate(anyLong());
        verify(stateMachine, never()).assertTransition(anyInt(), anyInt());
    }

    @Test
    void requestReturnsLatestStatusWhenCasLoses() {
        when(permissionService.requireMeeting(MEETING_ID)).thenReturn(meeting());
        when(minutesMapper.selectByMeetingId(MEETING_ID)).thenReturn(minutes(0, 0));
        when(segmentMapper.countByMinutesId(MINUTES_ID)).thenReturn(1L);
        when(minutesMapper.requestGenerate(MINUTES_ID)).thenReturn(0);
        // 并发 worker 已经把它推进到 20
        when(minutesMapper.selectById(MINUTES_ID)).thenReturn(minutes(20, 0));

        AiGenerateVO result = service.request(MEETING_ID, USER_ID);

        assertThat(result.getAiStatus()).isEqualTo(MinutesAiStatus.RUNNING.getCode());
        verify(minutesMapper).selectById(MINUTES_ID);
    }

    // ---------- process：成功 ----------

    @Test
    void processWritesSummaryOnSuccess() {
        stubPendingMinutes(0);
        stubTranscript();
        when(llmClient.completeJson(anyString(), anyString())).thenReturn(VALID_JSON);
        when(minutesMapper.markSuccess(eq(MINUTES_ID), anyString(), anyString(),
                any(LocalDateTime.class))).thenReturn(1);

        service.process(MINUTES_ID);

        verify(minutesMapper).markRunning(eq(MINUTES_ID), any(LocalDateTime.class));
        verify(llmClient).completeJson(eq(MinutesPromptBuilder.SYSTEM_PROMPT),
                contains("张三：你好"));
        verify(stateMachine).assertTransition(20, 30);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(minutesMapper).markSuccess(eq(MINUTES_ID), jsonCaptor.capture(),
                eq("deepseek-chat"), any(LocalDateTime.class));
        assertThat(jsonCaptor.getValue()).contains("\"summary\":\"摘要\"");
        verify(minutesMapper, never()).markRetry(anyLong(), anyString(),
                any(LocalDateTime.class), anyInt());
        verify(minutesMapper, never()).markFailed(anyLong(), anyString(),
                any(LocalDateTime.class), anyInt());
    }

    @Test
    void processChunksLongTranscriptThenMerges() {
        stubPendingMinutes(0);
        properties.getAi().setMaxInputChars(12);

        MinutesSpeaker first = new MinutesSpeaker();
        first.setSpeakerKey("0");
        first.setDisplayName("张三");
        MinutesSpeaker second = new MinutesSpeaker();
        second.setSpeakerKey("1");
        second.setDisplayName("李四");
        TranscriptSegment one = new TranscriptSegment();
        one.setSpeakerKey("0");
        one.setStartMs(0L);
        one.setText("第一段内容");
        TranscriptSegment two = new TranscriptSegment();
        two.setSpeakerKey("1");
        two.setStartMs(61_000L);
        two.setText("第二段内容");
        when(speakerMapper.selectByMinutesId(MINUTES_ID)).thenReturn(List.of(first, second));
        when(segmentMapper.selectByMinutesId(MINUTES_ID)).thenReturn(List.of(one, two));

        when(llmClient.completeJson(anyString(), anyString()))
                .thenReturn("要点一", "要点二", VALID_JSON);
        when(minutesMapper.markSuccess(eq(MINUTES_ID), anyString(), anyString(),
                any(LocalDateTime.class))).thenReturn(1);

        service.process(MINUTES_ID);

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(3)).completeJson(systemCaptor.capture(), userCaptor.capture());
        assertThat(systemCaptor.getAllValues()).containsExactly(
                MinutesPromptBuilder.CHUNK_SYSTEM_PROMPT,
                MinutesPromptBuilder.CHUNK_SYSTEM_PROMPT,
                MinutesPromptBuilder.SYSTEM_PROMPT);
        assertThat(userCaptor.getAllValues().get(2))
                .contains("片段 1", "片段 2", "要点一", "要点二");
        verify(minutesMapper).markSuccess(eq(MINUTES_ID), anyString(), anyString(),
                any(LocalDateTime.class));
    }

    // ---------- process：失败与重试 ----------

    @Test
    void processRetriesWhenAttemptsRemain() {
        stubPendingMinutes(0);
        stubTranscript();
        // 供应商原始异常可能包含响应体/密钥，绝不能进入 ai_error
        when(llmClient.completeJson(anyString(), anyString()))
                .thenThrow(new IllegalStateException(
                        "provider raw response: sk-secret-123 timeout"));
        when(minutesMapper.markRetry(eq(MINUTES_ID), anyString(),
                any(LocalDateTime.class), eq(1))).thenReturn(1);

        service.process(MINUTES_ID);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(minutesMapper).markRetry(eq(MINUTES_ID), errorCaptor.capture(),
                any(LocalDateTime.class), eq(1));
        assertThat(errorCaptor.getValue())
                .isEqualTo("AI 服务超时或不可用，请重试")
                .doesNotContain("sk-secret")
                .doesNotContain("provider raw");
        verify(stateMachine).assertTransition(20, 10);
        verify(minutesMapper, never()).markFailed(anyLong(), anyString(),
                any(LocalDateTime.class), anyInt());
    }

    @Test
    void processFailsWhenAttemptsExhausted() {
        stubPendingMinutes(2);
        stubTranscript();
        when(llmClient.completeJson(anyString(), anyString()))
                .thenThrow(new IllegalStateException("timeout"));
        when(minutesMapper.markFailed(eq(MINUTES_ID), anyString(),
                any(LocalDateTime.class), eq(3))).thenReturn(1);

        service.process(MINUTES_ID);

        verify(minutesMapper).markFailed(eq(MINUTES_ID), anyString(),
                any(LocalDateTime.class), eq(3));
        verify(stateMachine).assertTransition(20, 40);
        verify(minutesMapper, never()).markRetry(anyLong(), anyString(),
                any(LocalDateTime.class), anyInt());
    }

    @Test
    void processFailsWhenNoSegments() {
        properties.getAi().setMaxAttempts(1);
        stubPendingMinutes(0);
        when(speakerMapper.selectByMinutesId(MINUTES_ID)).thenReturn(List.of());
        when(segmentMapper.selectByMinutesId(MINUTES_ID)).thenReturn(List.of());
        when(minutesMapper.markFailed(eq(MINUTES_ID), anyString(),
                any(LocalDateTime.class), eq(1))).thenReturn(1);

        service.process(MINUTES_ID);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(minutesMapper).markFailed(eq(MINUTES_ID), errorCaptor.capture(),
                any(LocalDateTime.class), eq(1));
        assertThat(errorCaptor.getValue()).isEqualTo(ErrorCode.TRANSCRIPT_EMPTY.getMsg());
        verify(llmClient, never()).completeJson(anyString(), anyString());
        verify(stateMachine).assertTransition(20, 40);
    }

    @Test
    void processSkipsWhenMarkRunningCasLoses() {
        when(minutesMapper.selectById(MINUTES_ID)).thenReturn(minutes(10, 0));
        when(minutesMapper.markRunning(eq(MINUTES_ID), any(LocalDateTime.class))).thenReturn(0);

        service.process(MINUTES_ID);

        verify(llmClient, never()).completeJson(anyString(), anyString());
        verify(speakerMapper, never()).selectByMinutesId(anyLong());
        verify(segmentMapper, never()).selectByMinutesId(anyLong());
        verify(minutesMapper, never()).markSuccess(anyLong(), anyString(), anyString(),
                any(LocalDateTime.class));
    }

    @Test
    void processSkipsWhenNotPending() {
        when(minutesMapper.selectById(MINUTES_ID)).thenReturn(minutes(30, 0));

        service.process(MINUTES_ID);

        verify(minutesMapper, never()).markRunning(anyLong(), any(LocalDateTime.class));
        verify(llmClient, never()).completeJson(anyString(), anyString());
    }

    // ---------- CAS 0 行：只记日志，不抛异常 ----------

    @Test
    void processSuccessCasZeroDoesNotThrow() {
        stubPendingMinutes(0);
        stubTranscript();
        when(llmClient.completeJson(anyString(), anyString())).thenReturn(VALID_JSON);
        when(minutesMapper.markSuccess(eq(MINUTES_ID), anyString(), anyString(),
                any(LocalDateTime.class))).thenReturn(0);

        assertThatCode(() -> service.process(MINUTES_ID)).doesNotThrowAnyException();

        verify(minutesMapper).markSuccess(eq(MINUTES_ID), anyString(), anyString(),
                any(LocalDateTime.class));
        verify(minutesMapper, never()).markRetry(anyLong(), anyString(),
                any(LocalDateTime.class), anyInt());
    }

    @Test
    void processRetryCasZeroDoesNotThrow() {
        stubPendingMinutes(0);
        stubTranscript();
        when(llmClient.completeJson(anyString(), anyString()))
                .thenThrow(new IllegalStateException("timeout"));
        when(minutesMapper.markRetry(eq(MINUTES_ID), anyString(),
                any(LocalDateTime.class), anyInt())).thenReturn(0);

        assertThatCode(() -> service.process(MINUTES_ID)).doesNotThrowAnyException();

        verify(minutesMapper).markRetry(eq(MINUTES_ID), anyString(),
                any(LocalDateTime.class), eq(1));
    }

    @Test
    void processFailureCasZeroDoesNotThrow() {
        stubPendingMinutes(2);
        stubTranscript();
        when(llmClient.completeJson(anyString(), anyString()))
                .thenThrow(new IllegalStateException("timeout"));
        when(minutesMapper.markFailed(eq(MINUTES_ID), anyString(),
                any(LocalDateTime.class), anyInt())).thenReturn(0);

        assertThatCode(() -> service.process(MINUTES_ID)).doesNotThrowAnyException();

        verify(minutesMapper).markFailed(eq(MINUTES_ID), anyString(),
                any(LocalDateTime.class), eq(3));
    }

    // ---------- handleStuck ----------

    @Test
    void handleStuckRetriesWhenAttemptsRemain() {
        when(minutesMapper.selectById(MINUTES_ID)).thenReturn(minutes(20, 0));
        when(minutesMapper.markRetry(eq(MINUTES_ID), eq("生成超时，请重试"),
                any(LocalDateTime.class), eq(1))).thenReturn(1);

        service.handleStuck(MINUTES_ID);

        verify(minutesMapper).markRetry(eq(MINUTES_ID), eq("生成超时，请重试"),
                any(LocalDateTime.class), eq(1));
        verify(stateMachine).assertTransition(20, 10);
        verify(minutesMapper, never()).markFailed(anyLong(), anyString(),
                any(LocalDateTime.class), anyInt());
        verify(llmClient, never()).completeJson(anyString(), anyString());
    }

    @Test
    void handleStuckFailsWhenAttemptsExhausted() {
        when(minutesMapper.selectById(MINUTES_ID)).thenReturn(minutes(20, 2));
        when(minutesMapper.markFailed(eq(MINUTES_ID), eq("生成超时，请重试"),
                any(LocalDateTime.class), eq(3))).thenReturn(1);

        service.handleStuck(MINUTES_ID);

        verify(minutesMapper).markFailed(eq(MINUTES_ID), eq("生成超时，请重试"),
                any(LocalDateTime.class), eq(3));
        verify(stateMachine).assertTransition(20, 40);
    }

    @Test
    void handleStuckIgnoresNonRunningMinutes() {
        when(minutesMapper.selectById(MINUTES_ID)).thenReturn(minutes(30, 0));

        service.handleStuck(MINUTES_ID);

        verify(minutesMapper, never()).markRetry(anyLong(), anyString(),
                any(LocalDateTime.class), anyInt());
        verify(minutesMapper, never()).markFailed(anyLong(), anyString(),
                any(LocalDateTime.class), anyInt());
    }

    @Test
    void handleStuckCasZeroDoesNotThrow() {
        when(minutesMapper.selectById(MINUTES_ID)).thenReturn(minutes(20, 0));
        when(minutesMapper.markRetry(eq(MINUTES_ID), anyString(),
                any(LocalDateTime.class), anyInt())).thenReturn(0);

        assertThatCode(() -> service.handleStuck(MINUTES_ID)).doesNotThrowAnyException();

        verify(minutesMapper).markRetry(eq(MINUTES_ID), anyString(),
                any(LocalDateTime.class), eq(1));
    }
}
