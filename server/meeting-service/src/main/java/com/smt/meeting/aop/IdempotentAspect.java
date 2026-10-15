package com.smt.meeting.aop;

import cn.hutool.core.util.StrUtil;
import com.smt.common.context.UserContext;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/**
 * 幂等切面（T2.5）：拦截 {@link Idempotent} 注解方法，用 Redis SETNX 实现"同 token 窗口内只处理一次"。
 *
 * 执行时序：
 *   SETNX 成功（首次）→ 执行业务 → 成功保留 key（挡重放）/ 失败删 key（允许重试）
 *   SETNX 失败（重复）→ 直接抛 10006，业务方法不执行
 *
 * 与事务的关系：切面在 Controller 层（事务在 Service 层），先幂等判重、后进事务；
 * 并发下只有拿到 SETNX 的那个请求能进入业务，另一个立刻得到 10006——不需要额外加锁。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    public static final String HEADER_IDEMPOTENCY_TOKEN = "X-Idempotency-Token";

    private static final String KEY_PREFIX = "idem:";

    private final StringRedisTemplate redis;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        String token = resolveToken();
        if (token == null) {
            // 宽容策略：未带幂等头放行（前端按 04 文档约定必带；后端不强制以免破坏既有联调）
            log.warn("[Idempotent] 请求未携带 {}，跳过幂等保护: {}",
                    HEADER_IDEMPOTENCY_TOKEN, pjp.getSignature().toShortString());
            return pjp.proceed();
        }

        String key = KEY_PREFIX + idempotent.value() + ":" + UserContext.getUserId() + ":" + token;
        Boolean first = redis.opsForValue().setIfAbsent(key, "1", Duration.ofMinutes(idempotent.ttlMinutes()));
        if (!Boolean.TRUE.equals(first)) {
            // 窗口内重复提交：直接 10006（前端静默忽略，复用上次结果）
            log.warn("[Idempotent] 重复提交拦截: key={}", key);
            throw new BusinessException(ErrorCode.DUPLICATE_SUBMIT);
        }

        try {
            return pjp.proceed();
        } catch (Throwable e) {
            // 业务失败 → 删除 key：允许用户修改参数后用同一 token 重试
            redis.delete(key);
            throw e;
        }
    }

    private String resolveToken() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        String token = attributes.getRequest().getHeader(HEADER_IDEMPOTENCY_TOKEN);
        return StrUtil.isBlank(token) ? null : token.trim();
    }
}
