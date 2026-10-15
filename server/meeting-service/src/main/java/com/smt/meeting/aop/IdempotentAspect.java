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
 * 【key 的值带状态，不是单纯的标记】`P` = 处理中 / `S` = 已完成。
 * 原因（评审 R5）：原先 SETNX 一失败就直接回 10006，等于对"首个请求还在跑 / 已经失败"**谎报成功**——
 * 前端把 10006 当"已提交过"静默处理并关闭表单，若首个请求随后失败（冲突/抢锁失败），
 * 用户看到"已提交过"却什么都没订上，一次预约静默丢失。
 * 现在：已完成 → 10006（真重复）；处理中 → 短暂等待首个请求的结果；
 *      等待期间首个请求失败并删了 key → 自己接手执行，保住"失败可重试"的语义。
 *
 * 与事务的关系：切面在 Controller 层（事务在 Service 层），先幂等判重、后进事务；
 * 并发下只有拿到 SETNX 的那个请求能执行业务，其余按上面的状态机处理。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    public static final String HEADER_IDEMPOTENCY_TOKEN = "X-Idempotency-Token";

    private static final String KEY_PREFIX = "idem:";
    /** 处理中：首个请求已占坑、业务尚未出结果 */
    private static final String STATE_PROCESSING = "P";
    /** 已完成：业务成功，key 留到 TTL 到期挡重放 */
    private static final String STATE_SUCCEEDED = "S";
    /** 重复请求等待"首个请求出结果"的轮询：最多 20 × 100ms = 2 秒 */
    private static final int WAIT_ROUNDS = 20;
    private static final long WAIT_INTERVAL_MS = 100;

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
        Duration ttl = Duration.ofMinutes(idempotent.ttlMinutes());
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, STATE_PROCESSING, ttl))) {
            return executeAndMark(pjp, key, ttl);
        }
        return handleDuplicate(pjp, key, ttl);
    }

    /** 执行业务：成功把 key 标成 S（挡重放），失败删 key（允许改参重试） */
    private Object executeAndMark(ProceedingJoinPoint pjp, String key, Duration ttl) throws Throwable {
        try {
            Object result = pjp.proceed();
            redis.opsForValue().set(key, STATE_SUCCEEDED, ttl);
            return result;
        } catch (Throwable e) {
            redis.delete(key);
            throw e;
        }
    }

    /** 同上，但用于"接手"场景（首个请求已失败、key 已被删） */
    private Object handleDuplicate(ProceedingJoinPoint pjp, String key, Duration ttl) throws Throwable {
        for (int i = 0; i < WAIT_ROUNDS; i++) {
            String state = redis.opsForValue().get(key);
            if (STATE_SUCCEEDED.equals(state)) {
                log.warn("[Idempotent] 重复提交拦截（首个请求已完成）: key={}", key);
                throw new BusinessException(ErrorCode.DUPLICATE_SUBMIT);
            }
            if (state == null) {
                // 首个请求失败已删 key → 抢过来自己执行（保住"失败可重试"的语义）
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, STATE_PROCESSING, ttl))) {
                    log.info("[Idempotent] 首个请求未成功，重复请求接手执行: key={}", key);
                    return executeAndMark(pjp, key, ttl);
                }
                continue; // 被别的等待者抢到了，继续等
            }
            try {
                Thread.sleep(WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.SYSTEM_BUSY);
            }
        }
        // 等了 2 秒首个请求仍在处理中：如实回"忙"，让客户端稍后重试（不谎报 10006）
        log.warn("[Idempotent] 首个请求仍在处理中，拒绝重复提交: key={}", key);
        throw new BusinessException(ErrorCode.SYSTEM_BUSY);
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
