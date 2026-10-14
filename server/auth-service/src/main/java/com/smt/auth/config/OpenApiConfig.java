package com.smt.auth.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc 接口文档配置（Swagger UI: http://localhost:8081/swagger-ui/index.html）
 * 接口按 Controller 自动分组为 tag
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI authApiOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("会议室预约系统 · auth-service")
                .description("认证 / 双 token / 用户管理接口（对照 docs/04 接口设计）")
                .version("v1.0.0"));
    }
}
