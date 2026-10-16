package com.smt.meeting.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.smt.common.context.LoginUser;
import com.smt.common.context.UserContext;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.config.RateLimitProperties;

/**
 * T4.8 验收（单元级）：限流的**降级放行**与**超限拦截**。
 *
 * <p>为什么降级这条要用单测而不是"把 Redis 停掉试试"：
 * 本机 Redis 是**共享**的（auth 的 refreshToken、幂等键、缓存都在上面），
 * 停掉它会同时打断其他服务的验证，而且请求会先在幂等切面那里失败、
 * 根本走不到限流切面——测出来的现象指不到要验的结论。
 * 单测能精确地只让"限流这一段"出错（mock 掉 redis.execute 抛异常），这才是确定性验证。
 *
 * <p>三类断言：① 超限 → 10005；② 阈值 ≤ 0 → 关闭限流；③ **限流组件异常 → 放行**（核心降级语义）。
 */
class RateLimitAspectTest {

    private static final RateLimit ANNOTATION = new RateLimit() {
        @Override
        public Class<? extends java.lang.annotation.Annotation> annotationType() {
            return RateLimit.class;
        }

        @Override
        public String value() {
            return "reserve";
        }
    };

    private StringRedisTemplate redis;
    private RateLimitProperties properties;
    private RateLimitAspect aspect;
    private ProceedingJoinPoint pjp;

    @BeforeEach
    void setUp() throws Throwable {
        redis = mock(StringRedisTemplate.class);
        properties = new RateLimitProperties();
        aspect = new RateLimitAspect(redis, properties);
        pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");
        // 有身份才做用户级限流
        UserContext.set(new LoginUser(4L, List.of("USER")));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void overLimitShouldThrowRateLimited() throws Throwable {
        properties.setReservePerMinute(10);
        // Lua 返回 -1 = 超限
        when(redis.execute(any(), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(-1L);

        assertThatThrownBy(() -> aspect.around(pjp, ANNOTATION))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED.getCode());
    }

    @Test
    void withinLimitShouldProceed() throws Throwable {
        properties.setReservePerMinute(10);
        when(redis.execute(any(), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(1L);

        assertThat(aspect.around(pjp, ANNOTATION)).isEqualTo("ok");
    }

    @Test
    void limitZeroMeansDisabled() throws Throwable {
        properties.setReservePerMinute(0);
        // 阈值 0 = 关闭：连 Redis 都不该碰（返回 null 表示"没执行过"，用它来证明）
        assertThat(aspect.around(pjp, ANNOTATION)).isEqualTo("ok");
        org.mockito.Mockito.verify(redis, org.mockito.Mockito.never())
                .execute(any(), anyList(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void redisFailureShouldDegradeToPassThrough() throws Throwable {
        properties.setReservePerMinute(10);
        // 核心降级语义：限流组件挂了，业务照常走（限流是保护，不是业务前提）
        when(redis.execute(any(), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("Redis 挂了"));

        assertThat(aspect.around(pjp, ANNOTATION)).isEqualTo("ok");
    }
}
