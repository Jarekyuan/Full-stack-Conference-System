package com.smt.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilTest {

    /** HS256 要求密钥 ≥ 32 字节 */
    private static final String SECRET = "smt-unit-test-secret-key-0123456789-abcdefghijklmn";
    private final JwtUtil jwtUtil = new JwtUtil(SECRET);

    @Test
    void generateAndParseRoundTrip() {
        String token = jwtUtil.generate(4L, "USER", Duration.ofMinutes(30));

        Claims claims = jwtUtil.parse(token);

        assertEquals(4L, jwtUtil.getUserId(claims));
        assertEquals("USER", jwtUtil.getRole(claims));
    }

    @Test
    void tamperedTokenShouldFail() {
        String token = jwtUtil.generate(4L, "USER", Duration.ofMinutes(30));
        String tampered = token.substring(0, token.length() - 4) + "abcd";

        assertThrows(JwtException.class, () -> jwtUtil.parse(tampered));
    }

    @Test
    void expiredTokenShouldThrowExpiredException() {
        String expired = jwtUtil.generate(4L, "USER", Duration.ofSeconds(-1));

        assertThrows(ExpiredJwtException.class, () -> jwtUtil.parse(expired));
    }
}
