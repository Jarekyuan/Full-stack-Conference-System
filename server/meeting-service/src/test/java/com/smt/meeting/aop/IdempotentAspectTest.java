package com.smt.meeting.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.smt.common.context.LoginUser;
import com.smt.common.context.UserContext;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;

/**
 * T4.10 补齐：幂等切面（T2.5）的单测——此前只有脚本级的并发验证，逻辑分支没被逐个钉住。
 *
 * <p>重点验的是**评审 R5 那次修复**：key 的值带状态（P 处理中 / S 已完成），
 * 否则"首个请求还在跑 / 已经失败"都会被谎报成 10006——用户看到"已提交过"却什么都没订上。
 */
class IdempotentAspectTest {

    private static final String TOKEN = "tok-123";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private IdempotentAspect aspect;
    private ProceedingJoinPoint pjp;
    private String expectedKey;

    private final Idempotent annotation = new Idempotent() {
        @Override
        public Class<? extends java.lang.annotation.Annotation> annotationType() {
            return Idempotent.class;
        }

        @Override
        public String value() {
            return "meeting";
        }

        @Override
        public int ttlMinutes() {
            return 10;
        }
    };

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        aspect = new IdempotentAspect(redis);
        pjp = mock(ProceedingJoinPoint.class);
        // 切面的 WARN 日志会打 getSignature().toShortString()，mock 里不桩就是 NPE
        when(pjp.getSignature()).thenReturn(mock(org.aspectj.lang.Signature.class));

        // 幂等 token 从请求头取 → 造一个请求上下文
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(IdempotentAspect.HEADER_IDEMPOTENCY_TOKEN, TOKEN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        UserContext.set(new LoginUser(4L, java.util.List.of("USER")));
        expectedKey = "idem:meeting:4:" + TOKEN;
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        UserContext.clear();
    }

    @Test
    void firstRequestShouldProceedAndMarkSucceeded() throws Throwable {
        when(valueOps.setIfAbsent(eq(expectedKey), eq("P"), any(Duration.class))).thenReturn(true);
        when(pjp.proceed()).thenReturn("created");

        assertThat(aspect.around(pjp, annotation)).isEqualTo("created");
        verify(valueOps).set(expectedKey, "S", Duration.ofMinutes(10));
    }

    @Test
    void duplicateAfterSuccessShouldBeRejectedWith10006() throws Throwable {
        when(valueOps.setIfAbsent(eq(expectedKey), eq("P"), any(Duration.class))).thenReturn(false);
        when(valueOps.get(expectedKey)).thenReturn("S");

        assertThatThrownBy(() -> aspect.around(pjp, annotation))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.DUPLICATE_SUBMIT.getCode());
        verify(pjp, never()).proceed();
    }

    @Test
    void failedFirstRequestShouldDeleteKeySoRetryCanProceed() throws Throwable {
        when(valueOps.setIfAbsent(eq(expectedKey), eq("P"), any(Duration.class))).thenReturn(true);
        when(pjp.proceed()).thenThrow(new BusinessException(ErrorCode.TIME_CONFLICT));

        assertThatThrownBy(() -> aspect.around(pjp, annotation)).isInstanceOf(BusinessException.class);
        // 失败要删 key：否则用户改完参数重试会被自己的标记挡住（"失败也不让重试"是最糟的语义）
        verify(redis).delete(expectedKey);
        verify(valueOps, never()).set(eq(expectedKey), eq("S"), any(Duration.class));
    }

    @Test
    void inFlightFirstRequestShouldNotBeReportedAsDuplicate() throws Throwable {
        // 首个请求占着 P、迟迟没出结果：重复请求应当"如实说忙"，而不是谎报 10006
        when(valueOps.setIfAbsent(eq(expectedKey), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOps.get(expectedKey)).thenReturn("P");

        assertThatThrownBy(() -> aspect.around(pjp, annotation))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SYSTEM_BUSY.getCode());
        verify(pjp, never()).proceed();
    }

    @Test
    void failedFirstRequestLetsDuplicateTakeOver() throws Throwable {
        // 首个请求失败删了 key（get 返回 null）→ 重复请求应当"接手"执行，保住"失败可重试"
        when(valueOps.setIfAbsent(eq(expectedKey), anyString(), any(Duration.class))).thenReturn(false, true);
        when(valueOps.get(expectedKey)).thenReturn(null);
        when(pjp.proceed()).thenReturn("created-by-retry");

        assertThat(aspect.around(pjp, annotation)).isEqualTo("created-by-retry");
        verify(valueOps).set(eq(expectedKey), eq("S"), any(Duration.class));
    }

    @Test
    void missingTokenShouldPassThroughWithoutProtection() throws Throwable {
        MockHttpServletRequest bare = new MockHttpServletRequest(); // 没有幂等头
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(bare));
        when(pjp.proceed()).thenReturn("ok");

        assertThat(aspect.around(pjp, annotation)).isEqualTo("ok");
        verify(redis, never()).opsForValue();
    }
}
