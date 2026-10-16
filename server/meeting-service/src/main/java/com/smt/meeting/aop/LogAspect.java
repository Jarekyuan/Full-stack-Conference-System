package com.smt.meeting.aop;

import java.lang.reflect.Method;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.smt.common.context.UserContext;

/**
 * 操作审计切面（T4.11）：环绕执行，记录"谁在何时做了什么、成没成、耗时"。
 *
 * <h3>三条性质</h3>
 * ① **审计失败绝不能影响业务**（切面里 try/catch 全吞 + WARN 告警）：审计是旁路，
 *    为它把一笔已经成功的预约打成 500，是本末倒置。
 * ② **不记全文**：只取对象 id，见 {@link Log}。
 * ③ **顺序在事务之外**（{@code @Order} 靠前 → 包住 @Transactional）：
 *    这样耗时含事务、并且**事务提交失败也会被记成 FAIL**——如果切面在事务里面，
 *    "方法返回正常但提交阶段炸了"就会被审计记成 SUCCESS（审计最不能出的错就是记反）。
 *
 * <h3>⚠️ 自调用不生效</h3>
 * 同 {@link Log} 的说明：只对"外部进来"的调用生效（Spring AOP 的代理限制），别标内部私有方法。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class LogAspect {

    private final AuditLogger auditLogger;

    @Around("@annotation(logAnnotation)")
    public Object around(ProceedingJoinPoint pjp, Log logAnnotation) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            // 成功路径的审计本身也不能抛（catch 在 recordQuietly 里）
            recordQuietly(logAnnotation.type(), extractTargetId(pjp.getArgs(), result, logAnnotation),
                    true, System.currentTimeMillis() - start, null);
            return result;
        } catch (Throwable e) {
            // ⚠️ 失败路径**仍要看参数**：cancel/update/extend 的目标 id 就在参数里（单测逮过我传 null 的版本）；
            // 只有 targetFromResultOnly 的创建类操作才记 "-"
            recordQuietly(logAnnotation.type(), extractTargetId(pjp.getArgs(), null, logAnnotation),
                    false, System.currentTimeMillis() - start, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e; // 业务异常照常抛（审计只是旁路）
        }
    }

    /** 审计任何异常都吞掉并告警——它绝不能改变业务的成败 */
    private void recordQuietly(String type, Long targetId, boolean success, long elapsedMs, String error) {
        try {
            auditLogger.record(UserContext.getUserId(), type, targetId, success, elapsedMs, error);
        } catch (Exception e) {
            log.error("[审计] 记录失败（业务不受影响）: action={}, err={}", type, e.getMessage());
        }
    }

    /**
     * 尽力而为地取"对象 id"：**先看返回值，再看参数**——顺序不能反（单测逮到过）：
     * `create(dto, organiserId)` 的参数里第一个 Long 是**组织人 id**，不是刚建出来的会议 id，
     * 按"参数优先"会记下一行"谁被操作了 = 组织人自己"的假审计（错得还很像对的，最难发现）。
     *
     * <p>为什么不要求每个调用点显式传 id：那会让业务方法为了审计改签名（把审计成本转嫁给业务代码）。
     * 这里用"约定 + 兜底"——取不到就记 `-`，**审计宁可缺一个字段，不能因为取 id 失败而报错**。
     */
    private Long extractTargetId(Object[] args, Object result, Log annotation) {
        Long fromResult = result == null ? null : extractIdFromResult(result);
        if (fromResult != null) {
            return fromResult;
        }
        if (annotation.targetFromResultOnly()) {
            return null; // 创建类操作：参数里没有目标 id，取不到就记 "-"（宁可缺字段，不可记错 id）
        }
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Long id) {
                    return id; // 无返回值的写操作（cancel/update/extend/finish）：第一个 Long 就是目标 id
                }
            }
        }
        return null;
    }

    private Long extractIdFromResult(Object result) {
        if (result instanceof Long id) {
            return id;
        }
        for (String accessor : new String[]{"meetingId", "getMeetingId", "getId"}) {
            try {
                Method m = result.getClass().getMethod(accessor);
                if (m.getReturnType() == Long.class || m.getReturnType() == long.class) {
                    Object value = m.invoke(result);
                    return value == null ? null : ((Number) value).longValue();
                }
            } catch (ReflectiveOperationException ignored) {
                // 没有这个访问器就算了，换下一个约定
            }
        }
        return null;
    }
}
