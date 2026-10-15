package com.smt.auth.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * refreshToken 存储（Redis 双索引，见 docs/04 接口设计 4.1/4.2）：
 * - auth:refresh:{userId}              → refreshToken 的 **SHA-256 摘要**
 * - auth:refresh:token:{摘要}           → userId（refresh 时由 token 反查用户）
 * 同一用户只保留一个有效 refreshToken（新登录/滚动更新自动作废旧 token）
 *
 * 【只存摘要，不存明文】Redis 里落的是 SHA-256，明文只存在于客户端手里。
 * 原因：Redis 默认无密码、端口又常被发布到宿主机（评审 C1 实测：一条 nc 就能读走管理员
 * 的 refreshToken，再打白名单里的 /auth/refresh 直接换成 ADMIN 的 accessToken）。
 * 存摘要后，即使令牌库被整库读走，也拿不到可用于换 token 的原像。
 *
 * 【并发安全】三个写操作（save/rotate/removeByUser）全部用 Lua 脚本原子执行。
 * 原因：原实现是"读旧值→删旧反向索引→写两键"的多步读改写，并发刷新时会出现
 * "后写者覆盖用户索引但不删先写者反向索引"→ 先写者拿到的 refreshToken 立即失效 + 残留脏索引。
 * 前端 single-flight 只覆盖正常路径，服务端必须自保（评审 P0）。
 */
@Component
public class RefreshTokenStore {

    private static final String USER_KEY_PREFIX = "auth:refresh:";
    private static final String TOKEN_KEY_PREFIX = "auth:refresh:token:";

    /** 登录/重新登录：作废该用户旧 token → 写用户索引 → 写 token 反向索引（原子） */
    private static final RedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
            local old = redis.call('GET', KEYS[1])
            if old then redis.call('DEL', ARGV[3] .. old) end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('SET', KEYS[2], ARGV[4], 'PX', ARGV[2])
            return 1
            """, Long.class);

    /**
     * 刷新轮换（原子）：校验旧 token 有效 → 删旧反向索引 → 写入新 token 双索引，返回 userId。
     * 返回 null 表示旧 token 无效（不存在或已被轮换），调用方按 20003 处理。
     */
    private static final RedisScript<String> ROTATE_SCRIPT = new DefaultRedisScript<>("""
            local uid = redis.call('GET', KEYS[1])
            if not uid then return nil end
            local ukey = ARGV[4] .. uid
            local current = redis.call('GET', ukey)
            if current ~= ARGV[1] then return nil end
            redis.call('DEL', KEYS[1])
            redis.call('SET', ukey, ARGV[2], 'PX', ARGV[3])
            redis.call('SET', KEYS[2], uid, 'PX', ARGV[3])
            return uid
            """, String.class);

    /** 登出：删用户索引 + 反查删除反向索引（原子） */
    private static final RedisScript<Long> REMOVE_SCRIPT = new DefaultRedisScript<>("""
            local token = redis.call('GET', KEYS[1])
            if token then redis.call('DEL', ARGV[1] .. token) end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Duration refreshTokenTtl;

    public RefreshTokenStore(StringRedisTemplate redis,
                             @Value("${security.jwt.refresh-token-ttl:7d}") Duration refreshTokenTtl) {
        this.redis = redis;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    /** 轮换结果：新 refreshToken 与其所属 userId */
    public record Rotated(Long userId, String newToken) {
    }

    /** 保存（新登录）：先作废该用户旧 token，再写两条索引，整体原子；落库的只有摘要 */
    public void save(Long userId, String refreshToken) {
        redis.execute(SAVE_SCRIPT,
                List.of(userKey(userId), digestKey(refreshToken)),
                digest(refreshToken), String.valueOf(refreshTokenTtl.toMillis()),
                TOKEN_KEY_PREFIX, String.valueOf(userId));
    }

    /**
     * 刷新：校验旧 token 并原子轮换出新 token（旧 token 即刻失效），成功返回 userId + 新 token。
     * 校验与轮换在同一段 Lua 内完成，两个并发请求持同一旧 token 时只有一个能成功。
     */
    public Rotated rotate(String oldToken) {
        String newToken = UUID.randomUUID().toString();
        String userId = redis.execute(ROTATE_SCRIPT,
                List.of(digestKey(oldToken), digestKey(newToken)),
                digest(oldToken), digest(newToken), String.valueOf(refreshTokenTtl.toMillis()),
                USER_KEY_PREFIX);
        if (userId == null) {
            return null;
        }
        return new Rotated(Long.valueOf(userId), newToken);
    }

    /** 登出/停用：作废该用户 refreshToken（双索引同清，原子） */
    public void removeByUser(Long userId) {
        redis.execute(REMOVE_SCRIPT, List.of(userKey(userId)), TOKEN_KEY_PREFIX);
    }

    private String userKey(Long userId) {
        return USER_KEY_PREFIX + userId;
    }

    /** 反向索引 key：由**摘要**拼成，Redis 里不出现明文 token */
    private String digestKey(String refreshToken) {
        return TOKEN_KEY_PREFIX + digest(refreshToken);
    }

    /** SHA-256 摘要（十六进制小写） */
    private static String digest(String refreshToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e); // JDK 必备算法，实际不可达
        }
    }
}
