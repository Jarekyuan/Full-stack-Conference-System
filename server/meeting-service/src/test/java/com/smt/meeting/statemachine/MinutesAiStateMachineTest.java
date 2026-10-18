package com.smt.meeting.statemachine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纪要 AI 状态机：逐条覆盖允许的 7 条迁移 + 2 条典型非法迁移。
 *
 * <p>状态含义见 {@code MinutesAiStatus}：0 未生成 / 10 待生成 / 20 生成中 / 30 成功 / 40 失败。</p>
 */
class MinutesAiStateMachineTest {

    private final MinutesAiStateMachine machine = new MinutesAiStateMachine();

    @Test
    void allowsNotGeneratedToPending() {
        assertThatCode(() -> machine.assertTransition(0, 10)).doesNotThrowAnyException();
    }

    @Test
    void allowsPendingToRunning() {
        assertThatCode(() -> machine.assertTransition(10, 20)).doesNotThrowAnyException();
    }

    @Test
    void allowsRunningToSuccess() {
        assertThatCode(() -> machine.assertTransition(20, 30)).doesNotThrowAnyException();
    }

    @Test
    void allowsRunningToPendingForRetry() {
        assertThatCode(() -> machine.assertTransition(20, 10)).doesNotThrowAnyException();
    }

    @Test
    void allowsRunningToFailed() {
        assertThatCode(() -> machine.assertTransition(20, 40)).doesNotThrowAnyException();
    }

    @Test
    void allowsSuccessToPendingForRegenerate() {
        assertThatCode(() -> machine.assertTransition(30, 10)).doesNotThrowAnyException();
    }

    @Test
    void allowsFailedToPendingForRegenerate() {
        assertThatCode(() -> machine.assertTransition(40, 10)).doesNotThrowAnyException();
    }

    @Test
    void rejectsPendingToSuccess() {
        assertThatThrownBy(() -> machine.assertTransition(10, 30))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING")
                .hasMessageContaining("SUCCESS");
    }

    @Test
    void rejectsNotGeneratedToRunning() {
        assertThatThrownBy(() -> machine.assertTransition(0, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_GENERATED")
                .hasMessageContaining("RUNNING");
    }
}
