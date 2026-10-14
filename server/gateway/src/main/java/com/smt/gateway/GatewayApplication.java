package com.smt.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * API 网关启动类（:8080）
 * 统一入口：路由转发到 auth / meeting / notification 服务（spring.cloud.gateway.routes）
 * 鉴权：JwtAuthFilter（白名单 + JWT 校验 + 身份头注入）
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
