package com.smt.meeting.service;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.entity.Meeting;
import com.smt.meeting.entity.RoomStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T5.1/T5.2 评审修复的纯函数单测：
 * ① 系列取消按**时间**三分类（不能只看 status，见评审 C1）；
 * ② R-6 取消截止对整系列全有或全无（评审 R4）。
 *
 * <p>不启动 Spring：分类与截止检查都是静态纯函数，直接把 Meeting/RoomStrategy 组装出来。
 */
class RecurringServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 17, 12, 0);

    private static Meeting meeting(int status, LocalDateTime start, LocalDateTime end) {
        Meeting m = new Meeting();
        m.setId((long) (Math.abs(start.hashCode()) + status));
        m.setStatus(status);
        m.setStartTime(start);
        m.setEndTime(end);
        return m;
    }

    // ---------- 分类 ----------

    @Test
    void futurePendingAndReservedAreCancellable() {
        Meeting pending = meeting(10, NOW.plusHours(2), NOW.plusHours(3));
        Meeting reserved = meeting(20, NOW.plusDays(1), NOW.plusDays(1).plusHours(1));

        RecurringService.CancelPlan plan = RecurringService.classifyCancel(List.of(pending, reserved), NOW);

        assertThat(plan.cancellable()).containsExactly(pending, reserved);
        assertThat(plan.ongoing()).isEmpty();
    }

    @Test
    void startedReservedIsOngoingEvenIfSchedulerHasNotMovedItTo30() {
        // 状态任务 60s 一轮：12:00 开始、12:00:30 时状态仍是 20，不能取消
        Meeting started = meeting(20, NOW.minusMinutes(5), NOW.plusMinutes(55));

        RecurringService.CancelPlan plan = RecurringService.classifyCancel(List.of(started), NOW);

        assertThat(plan.cancellable()).isEmpty();
        assertThat(plan.ongoing()).containsExactly(started);
    }

    @Test
    void startedOnGoingIsOngoing() {
        Meeting ongoing = meeting(30, NOW.minusMinutes(10), NOW.plusMinutes(20));
        RecurringService.CancelPlan plan = RecurringService.classifyCancel(List.of(ongoing), NOW);
        assertThat(plan.ongoing()).containsExactly(ongoing);
    }

    @Test
    void stuckPendingInItsTimeWindowIsTreatedAsOngoing() {
        // 时间已到但没人审批的 10：在它的时间窗内按进行中拦截；结束后才是历史
        Meeting stuck = meeting(10, NOW.minusMinutes(5), NOW.plusMinutes(25));
        RecurringService.CancelPlan plan = RecurringService.classifyCancel(List.of(stuck), NOW);
        assertThat(plan.ongoing()).containsExactly(stuck);
    }

    @Test
    void pastPendingReservedAndFinishedAreSkipped() {
        Meeting pastPending = meeting(10, NOW.minusDays(2), NOW.minusDays(2).plusHours(1));
        Meeting pastReserved = meeting(20, NOW.minusHours(2), NOW.minusHours(1));
        // 提前结束（40）但 endTime 在未来：终态跳过，不算进行中
        Meeting earlyFinished = meeting(40, NOW.minusMinutes(10), NOW.plusMinutes(50));

        RecurringService.CancelPlan plan = RecurringService.classifyCancel(
                List.of(pastPending, pastReserved, earlyFinished), NOW);

        assertThat(plan.cancellable()).isEmpty();
        assertThat(plan.ongoing()).isEmpty();
    }

    @Test
    void terminalStatusesAreSkippedEvenInFuture() {
        Meeting cancelled = meeting(50, NOW.plusDays(1), NOW.plusDays(1).plusHours(1));
        Meeting rejected = meeting(60, NOW.plusDays(2), NOW.plusDays(2).plusHours(1));

        RecurringService.CancelPlan plan = RecurringService.classifyCancel(List.of(cancelled, rejected), NOW);

        assertThat(plan.cancellable()).isEmpty();
        assertThat(plan.ongoing()).isEmpty();
    }

    @Test
    void dirtyRowsAreSkipped() {
        Meeting noStatus = meeting(0, NOW.plusDays(1), NOW.plusDays(1).plusHours(1));
        noStatus.setStatus(null);
        Meeting noStart = meeting(20, NOW.plusDays(1), NOW.plusDays(1).plusHours(1));
        noStart.setStartTime(null);

        RecurringService.CancelPlan plan = RecurringService.classifyCancel(List.of(noStatus, noStart), NOW);

        assertThat(plan.cancellable()).isEmpty();
        assertThat(plan.ongoing()).isEmpty();
    }

    // ---------- R-6 ----------

    @Test
    void cancelDeadlineRejectsWhenWithinDeadline() {
        RoomStrategy strategy = new RoomStrategy();
        strategy.setCancelDeadlineMinutes(30);
        Meeting soon = meeting(20, NOW.plusMinutes(10), NOW.plusMinutes(40));

        assertThatThrownBy(() -> RecurringService.ensureCancelDeadline(List.of(soon), strategy, NOW))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.CANCEL_DEADLINE_PASSED.getCode());
    }

    @Test
    void cancelDeadlineAllowsWhenOutsideDeadline() {
        RoomStrategy strategy = new RoomStrategy();
        strategy.setCancelDeadlineMinutes(30);
        Meeting later = meeting(20, NOW.plusMinutes(31), NOW.plusMinutes(61));

        assertThatCode(() -> RecurringService.ensureCancelDeadline(List.of(later), strategy, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void cancelDeadlineIsNoopWhenStrategyOrDeadlineMissing() {
        Meeting soon = meeting(20, NOW.plusMinutes(10), NOW.plusMinutes(40));
        assertThatCode(() -> RecurringService.ensureCancelDeadline(List.of(soon), null, NOW))
                .doesNotThrowAnyException();

        RoomStrategy zero = new RoomStrategy();
        zero.setCancelDeadlineMinutes(0);
        assertThatCode(() -> RecurringService.ensureCancelDeadline(List.of(soon), zero, NOW))
                .doesNotThrowAnyException();
    }
}
