package com.smt.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;

/**
 * JWT 工具（jjwt 0.12.x 新 API）：签发与解析 accessToken
 * 载荷约定：sub=userId，claim roles=角色 code 数组（RBAC 多角色，见 docs/04 接口设计 4.1）
 * 实例由各服务配置类创建（@Bean），secret 从配置读取
 */
public class JwtUtil {

    private static final String CLAIM_ROLES = "roles";

    private final SecretKey key;

    public JwtUtil(String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generate(Long userId, List<String> roles, Duration ttl) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_ROLES, roles)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(key)
                .compact();
    }

    /** 解析并校验签名与有效期；失败抛 io.jsonwebtoken.JwtException 子类（过期/篡改），由调用方处理 */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    /** 角色 code 列表（无 roles claim 时返回空列表，调用方按"无角色"处理） */
    public List<String> getRoles(Claims claims) {
        List<?> raw = claims.get(CLAIM_ROLES, List.class);
        if (raw == null) {
            return List.of();
        }
        return raw.stream().map(String::valueOf).toList();
    }
}
