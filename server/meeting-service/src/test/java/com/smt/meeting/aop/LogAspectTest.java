package com.smt.meeting.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.smt.common.context.LoginUser;
import com.smt.common.context.UserContext;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;

/**
 * T4.11 验收（单元级）：审计切面的三条性质。
 *
 * <p>"审计失败不阻断业务"这条**只能这样验**：真实环境里很难让"记日志"这一步失败
 * （除非磁盘满/权限错，那种实验做不了也不该做）。把落盘抽成可注入的 {@link AuditLogger} 之后，
 * 单测可以让它在记录时抛错，确定性地断言"业务照常返回"。
 */
class LogAspectTest {

    private AuditLogger auditLogger;
    private LogAspect aspect;
    private ProceedingJoinPoint pjp;

    private final Log annotation = new Log() {
        @Override
        public Class<? extends java.lang.annotation.Annotation> annotationType() {
            return Log.class;
        }

        @Override
        public String type() {
            return "MEETING_CANCEL";
        }
    };

    @BeforeEach
    void setUp() {
        auditLogger = mock(AuditLogger.class);
        aspect = new LogAspect(auditLogger);
        pjp = mock(ProceedingJoinPoint.class);
        UserContext.set(new LoginUser(4L, java.util.List.of("USER")));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void successShouldRecordOperatorActionTargetAndCost() throws Throwable {
        when(pjp.getArgs()).thenReturn(new Object[]{100L, "议题变更", 4L});
        when(pjp.proceed()).thenReturn(null);

        aspect.around(pjp, annotation);

        ArgumentCaptor<Long> target = ArgumentCaptor.forClass(Long.class);
        verify(auditLogger).record(eq(4L), eq("MEETING_CANCEL"), target.capture(), eq(true), anyLong(), isNull());
        // 取的是**参数里第一个 Long**（会议 id）；审计里**不记** "议题变更" 这类正文
        assertThat(target.getValue()).isEqualTo(100L);
    }

    @Test
    void businessFailureShouldBeRecordedAsFailAndRethrown() throws Throwable {
        when(pjp.getArgs()).thenReturn(new Object[]{100L, "原因", 4L});
        when(pjp.proceed()).thenThrow(new BusinessException(ErrorCode.NOT_MEETING_OWNER));

        assertThatThrownBy(() -> aspect.around(pjp, annotation)).isInstanceOf(BusinessException.class);

        // 失败也要留痕：审计的价值恰恰在于"谁的操作没成功"
        verify(auditLogger).record(eq(4L), eq("MEETING_CANCEL"), eq(100L), eq(false), anyLong(),
                eq("BusinessException: " + ErrorCode.NOT_MEETING_OWNER.getMsg()));
    }

    @Test
    void createdIdShouldComeFromResultWhenNoLongArgument() throws Throwable {
        // 创建场景：id 是"做出来"的，只能在返回值里找（按 meetingId() 约定）
        when(pjp.getArgs()).thenReturn(new Object[]{"dto", 4L});
        when(pjp.proceed()).thenReturn(new Object() {
            @SuppressWarnings("unused")
            public Long meetingId() {
                return 777L;
            }
        });

        aspect.around(pjp, annotation);

        verify(auditLogger).record(eq(4L), eq("MEETING_CANCEL"), eq(777L), eq(true), anyLong(), isNull());
    }

    @Test
    void auditFailureShouldNotBreakBusiness() throws Throwable {
        when(pjp.getArgs()).thenReturn(new Object[]{100L, "原因", 4L});
        when(pjp.proceed()).thenReturn("ok");
        // 审计组件炸了（磁盘满/权限错/日志框架异常）
        doThrow(new RuntimeException("磁盘满")).when(auditLogger)
                .record(any(), anyString(), any(), anyBoolean(), anyLong(), any());

        // 业务必须照常返回——为审计把一笔成功的预约打成 500 是本末倒置
        assertThat(aspect.around(pjp, annotation)).isEqualTo("ok");
    }
}
