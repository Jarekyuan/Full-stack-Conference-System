package com.smt.auth.config;

import com.smt.common.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 认证相关 Bean：JwtUtil（与 gateway 共用同一 secret）+ BCrypt 编码器
 */
@Configuration
public class AuthConfig {

    @Bean
    public JwtUtil jwtUtil(@Value("${security.jwt.secret}") String secret) {
        return new JwtUtil(secret);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
