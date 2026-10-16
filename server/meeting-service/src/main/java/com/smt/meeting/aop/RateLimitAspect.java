package com.smt.meeting.aop;

import java.util.List;
import java.util.UUID;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.smt.common.context.UserContext;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.config.RateLimitProperties;

/**
 * 用户级滑动窗口限流（T4.8）。
 *
 * <h3>为什么用 ZSET 而不是"计数器 + 固定窗口"</h3>
 * 固定窗口（INCR + EXPIRE）会在**窗口边界**放过几乎两倍的流量：第 59 秒发 10 次、第 61 秒再发 10 次，
 * 两个窗口各自"没超"，实际是 2 秒内 20 次。ZSET 存的是每次请求的时间戳，
 * 判定时先剔掉窗口外的、再数剩下的——窗口是**滑动**的，不存在这个边界漏洞。
 * 代价是每次请求要多几次 Redis 操作、且要存 N 个成员（都是毫秒级、量级可控）。
 *
 * <h3>为什么用 Lua 脚本</h3>
 * "清理过期 + 计数 + 判断 + 写入"四步必须**原子**：分成四条命令的话，
 * 同一用户的并发请求会各自读到"删除前"的计数，一起放行（限流形同虚设）。
 * Redis 单线程执行 Lua，整个过程不会被插入其他命令。★这也是面试常问的"限流怎么做才准"。
 *
 * <h3>降级：限流不可用时**放行**</h3>
 * Redis 抖动/连不上时不能把预约接口打成 500——限流是**保护**手段，不是业务前提。
 * 这与"非核心依赖不拖垮核心链路"是同一条原则（对比：分布式锁不可用时必须失败，
 * 因为那是**正确性**依赖，两者取舍方向相反，见 02 文档的五、取舍表）。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    /** key 与 docs/02 6.4 的键设计一致：limit:{业务}:{userId} */
    private static final String KEY_PREFIX = "limit:";

    /**
     * 滑动窗口（原子）：
     * ① 删掉窗口外的旧成员 ② 数剩下的 ③ 超了就返回 -1（**不写入**）④ 否则记下本次并续期。
     * member 用 "时间戳-随机串"：同一毫秒内的两次请求如果用同一个 member，ZADD 会覆盖而不是新增，计数就少了。
     */
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local windowMs = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            redis.call('ZREMRANGEBYSCORE', key, 0, now - windowMs)
            local count = redis.call('ZCARD', key)
            if count >= limit then
                return -1
            end
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, windowMs)
            return count + 1
            """, Long.class);

    private final StringRedisTemplate redis;
    /** ⚠️ 必须注入 @RefreshScope 的配置 Bean，且**每次调用现读**——否则热更新失效（见该类注释） */
    private final RateLimitProperties properties;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        try {
            int limit = properties.getReservePerMinute();
            if (limit > 0 && !tryAcquire(rateLimit.value(), limit, properties.getWindowSeconds())) {
                log.warn("[限流] 触发限流: user={}, 业务={}, 阈值={}/{}s",
                        UserContext.getUserId(), rateLimit.value(), limit, properties.getWindowSeconds());
                throw new BusinessException(ErrorCode.RATE_LIMITED);
            }
        } catch (BusinessException e) {
            throw e; // 超限是**正常业务分支**，不能被下面的兜底 catch 吞掉
        } catch (Exception e) {
            // 降级放行（见类注释）：Redis 不可用、Lua 执行失败、配置读取异常……都放行
            log.warn("[限流] 限流组件异常，本次放行（降级）: {}", e.getMessage());
        }
        return pjp.proceed();
    }

    private boolean tryAcquire(String business, int limit, int windowSeconds) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            // 没有身份就没法做"用户级"限流：放行（网关注入身份，这里为空说明是直连排障）
            return true;
        }
        String key = KEY_PREFIX + business + ":" + userId;
        long now = System.currentTimeMillis();
        Long result = redis.execute(SLIDING_WINDOW_SCRIPT, List.of(key),
                String.valueOf(now),
                String.valueOf(windowSeconds * 1000L),
                String.valueOf(limit),
                now + "-" + UUID.randomUUID());
        return result != null && result > 0;
    }
}
