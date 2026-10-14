package com.smt.auth.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * refreshToken 存储（Redis 双索引，见 docs/04 接口设计 4.1/4.2）：
 * - auth:refresh:{userId}       → refreshToken（按用户管理/登出用，04 文档约定的 key）
 * - auth:refresh:token:{token}  → userId（refresh 时由 token 反查用户）
 * 同一用户只保留一个有效 refreshToken（新登录/滚动更新自动作废旧 token）
 */
@Component
public class RefreshTokenStore {

    private static final String USER_KEY_PREFIX = "auth:refresh:";
    private static final String TOKEN_KEY_PREFIX = "auth:refresh:token:";

    private final StringRedisTemplate redis;
    private final Duration refreshTokenTtl;

    public RefreshTokenStore(StringRedisTemplate redis,
                             @Value("${security.jwt.refresh-token-ttl:7d}") Duration refreshTokenTtl) {
        this.redis = redis;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    /** 保存（新登录/滚动更新）：先作废该用户旧 token，再写两条索引 */
    public void save(Long userId, String refreshToken) {
        String oldToken = redis.opsForValue().get(userKey(userId));
        if (oldToken != null) {
            redis.delete(tokenKey(oldToken));
        }
        redis.opsForValue().set(userKey(userId), refreshToken, refreshTokenTtl);
        redis.opsForValue().set(tokenKey(refreshToken), String.valueOf(userId), refreshTokenTtl);
    }

    /** 由 refreshToken 反查 userId（已失效返回 null） */
    public Long findUserIdByToken(String refreshToken) {
        String value = redis.opsForValue().get(tokenKey(refreshToken));
        return value == null ? null : Long.valueOf(value);
    }

    /** token 是否为该用户当前有效 refreshToken（滚动更新后旧 token 比对失败） */
    public boolean isValid(Long userId, String refreshToken) {
        return refreshToken.equals(redis.opsForValue().get(userKey(userId)));
    }

    /** 登出：作废该用户 refreshToken（双索引同清） */
    public void removeByUser(Long userId) {
        String token = redis.opsForValue().get(userKey(userId));
        if (token != null) {
            redis.delete(tokenKey(token));
        }
        redis.delete(userKey(userId));
    }

    private String userKey(Long userId) {
        return USER_KEY_PREFIX + userId;
    }

    private String tokenKey(String refreshToken) {
        return TOKEN_KEY_PREFIX + refreshToken;
    }
}
