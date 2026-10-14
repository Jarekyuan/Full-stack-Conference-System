package com.smt.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.security.reactive.ReactiveManagementWebSecurityAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * API 网关启动类（:8080）
 * 统一入口：路由转发到 auth / meeting / notification 服务（spring.cloud.gateway.routes）
 * 鉴权：JwtAuthFilter（白名单 + JWT 校验 + 身份头注入）
 *
 * 排除 Spring Security 自动配置的原因（踩坑记录）：
 * gateway 是 WebFlux 应用，但它通过 meeting-common 间接拿到了 spring-security-core，
 * 触发 Boot 的默认安全链 → 所有 POST 被 CSRF 拦成 403（"An expected CSRF token cannot be found"），
 * 而 GET 因 JwtAuthFilter 抢先返回 401 侥幸不受影响，问题很隐蔽。
 * 本服务鉴权完全由 JwtAuthFilter 承担（02 文档：网关只做路由 + JWT 校验），
 * Security 的方法级鉴权只在 auth/meeting 这类 Servlet 业务服务里启用，故网关直接排除。
 */
// 注意：actuator 的 Management*WebSecurityAutoConfiguration 也必须一并排除，
// 否则它仍会去找 ServerHttpSecurity（已被上面的排除干掉）而启动失败
@SpringBootApplication(exclude = {
        SecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
        ReactiveSecurityAutoConfiguration.class,
        ReactiveUserDetailsServiceAutoConfiguration.class,
        ManagementWebSecurityAutoConfiguration.class,
        ReactiveManagementWebSecurityAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
